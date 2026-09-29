"""
Member-facing claim service.

Locking order is always: member's User row, then the OrderOffer row. The
payout-method services lock the same User row first, so a claim can never
interleave with the member deactivating or switching the method being
attached, and the fixed order rules out deadlocks.

The User lock also serializes a member's retries: the idempotency check
runs under it, so two requests with the same key cannot both create an
Order. The (user, idempotency_key) unique constraint remains the final
guard (e.g. on SQLite, where row locks are a no-op).
"""
from dataclasses import dataclass
from datetime import datetime
from decimal import Decimal

from django.contrib.auth import get_user_model
from django.db import IntegrityError, transaction
from django.db.models import F, Q
from django.utils import timezone

from audit.models import AuditEvent, LedgerEntry
from audit.services import record_audit_event, record_ledger_entry
from core.permissions import is_active_admin, is_verified_member
from orders.calculations import calculate_income_amount, calculate_payout_target
from orders.exceptions import (
    AdminOrderReadPermissionDenied,
    ClaimConflict,
    ClaimPermissionDenied,
    IdempotencyKeyConflict,
    InvalidIdempotencyKey,
    InvalidOffer,
    InvalidOfferIdempotencyKey,
    NoActivePayoutMethod,
    OfferCreateConflict,
    OfferCreatePermissionDenied,
    OfferIdempotencyKeyConflict,
    OfferNotFound,
    OfferSoldOut,
    OfferUnavailable,
    OrderNotFound,
    OrderReadPermissionDenied,
    PayoutMethodUnavailable,
)
from orders.models import Currency, Order, OrderOffer
from paymethods.models import UserPayoutMethod
from paymethods.upi_qr import UpiQrError, decode_upi_qr, parse_upi_payload

User = get_user_model()

IDEMPOTENCY_KEY_MAX_LENGTH = Order._meta.get_field('idempotency_key').max_length
OFFER_IDEMPOTENCY_KEY_MAX_LENGTH = OrderOffer._meta.get_field('idempotency_key').max_length


def claim_order(*, user, offer_id, idempotency_key, payout_method_id=None):
    """
    Claim `offer_id` for `user` and return the new Order (PAYMENT_REQUIRED).

    payout_method_id: one of the member's active methods; when omitted the
    member's default method is used. It is fixed on the Order for good.

    Retrying with the same idempotency_key returns the original Order
    without taking quantity or writing a ledger entry again - even if the
    offer has since become unavailable. Reusing the key for a different
    offer or a different explicit payout method raises
    IdempotencyKeyConflict.
    """
    if not is_verified_member(user):
        raise ClaimPermissionDenied()
    _validate_idempotency_key(idempotency_key)

    try:
        with transaction.atomic():
            _lock_user(user)

            existing = _existing_claim(user, idempotency_key)
            if existing is not None:
                return _replay(existing, offer_id, payout_method_id)

            payout_method = _resolve_payout_method(user, payout_method_id)
            offer = _lock_claimable_offer(offer_id)
            _take_one_unit(offer)

            income_amount = calculate_income_amount(offer.order_amount, offer.income_rate)
            order = Order.objects.create(
                user=user,
                offer=offer,
                currency=offer.currency,
                order_amount=offer.order_amount,
                income_rate=offer.income_rate,
                income_amount=income_amount,
                payout_target=calculate_payout_target(offer.order_amount, income_amount),
                payout_method=payout_method,
                # Snapshot: later edits to the offer never move this order's
                # pay-in destination.
                pay_in_upi_id=offer.pay_in_upi_id,
                pay_in_qr_payload=offer.pay_in_qr_payload,
                status=Order.Status.PAYMENT_REQUIRED,
                idempotency_key=idempotency_key,
            )
            # Nothing is owed until the pay-in is confirmed, so the claim
            # records the order amount without an outstanding balance.
            record_ledger_entry(
                actor=user,
                action=LedgerEntry.Action.ORDER_CLAIMED,
                order=order,
                amount=order.order_amount,
                reference_id=order.reference,
            )
            return order
    except IntegrityError:
        # A concurrent request with the same key won the race; everything
        # this attempt did (including the quantity decrement) rolled back.
        existing = _existing_claim(user, idempotency_key)
        if existing is not None:
            return _replay(existing, offer_id, payout_method_id)
        raise ClaimConflict()


def _validate_idempotency_key(key):
    if not isinstance(key, str) or not key.strip() or len(key) > IDEMPOTENCY_KEY_MAX_LENGTH:
        raise InvalidIdempotencyKey()


def _lock_user(user):
    User.objects.select_for_update().only('pk').get(pk=user.pk)


def _existing_claim(user, idempotency_key):
    return Order.objects.filter(user=user, idempotency_key=idempotency_key).first()


def _replay(order, offer_id, payout_method_id):
    if order.offer_id != _as_pk(offer_id):
        raise IdempotencyKeyConflict()
    if payout_method_id is not None and order.payout_method_id != _as_pk(payout_method_id):
        raise IdempotencyKeyConflict()
    return order


def _as_pk(value):
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def _resolve_payout_method(user, payout_method_id):
    active = UserPayoutMethod.objects.filter(user=user, is_active=True)
    if payout_method_id is None:
        method = active.filter(is_default=True).first()
        if method is None:
            if active.exists():
                # Active methods but no default: make the member choose.
                raise PayoutMethodUnavailable()
            raise NoActivePayoutMethod()
        return method

    method = active.filter(pk=_as_pk(payout_method_id)).first()
    if method is None:
        if not active.exists():
            raise NoActivePayoutMethod()
        raise PayoutMethodUnavailable()
    return method


def _lock_claimable_offer(offer_id):
    pk = _as_pk(offer_id)
    offer = OrderOffer.objects.select_for_update().filter(pk=pk).first() if pk else None
    if offer is None:
        raise OfferNotFound()

    now = timezone.now()
    if not offer.is_active:
        raise OfferUnavailable()
    if offer.valid_from is not None and now < offer.valid_from:
        raise OfferUnavailable()
    if offer.valid_to is not None and now >= offer.valid_to:
        raise OfferUnavailable()
    if offer.quantity_available is not None and offer.quantity_available <= 0:
        raise OfferSoldOut()
    return offer


def _take_one_unit(offer):
    if offer.quantity_available is None:
        return  # no quantity limit
    # Conditional decrement: the DB only succeeds while a unit remains, so
    # the count can never go negative even if the row lock were missing.
    taken = OrderOffer.objects.filter(pk=offer.pk, quantity_available__gt=0).update(
        quantity_available=F('quantity_available') - 1,
        updated_at=timezone.now(),
    )
    if taken != 1:
        raise OfferSoldOut()


# --- admin offer creation -----------------------------------------------------

def create_offer(
    *,
    admin,
    order_amount,
    income_rate,
    quantity_available,
    is_active,
    pay_in_qr,
    idempotency_key,
    ip_address=None,
):
    """
    Create an OrderOffer for an active admin and return it.

    order_amount: positive whole rupees. income_rate: 0-100 (2 dp).
    quantity_available: None for no limit, else at least 1. No validity
    window is set here. Income and payout target are not stored on the
    offer: claim_order calculates and snapshots them. pay_in_qr (required)
    is the uploaded image of the UPI QR members pay for orders claimed from
    this offer. It is decoded here; the offer stores the decoded upi://pay
    text and its pa (payee UPI ID), and claim_order snapshots both onto each
    Order. A QR carrying a fixed amount is accepted only if that amount is
    the order amount.

    Retrying with the same idempotency_key and the same values returns the
    original offer without writing again. Reusing the key with different
    values, or by another admin, raises OfferIdempotencyKeyConflict. Each
    new offer records one OFFER_CHANGED AuditEvent naming the admin.
    """
    if not is_active_admin(admin):
        raise OfferCreatePermissionDenied()
    if (
        not isinstance(idempotency_key, str)
        or not idempotency_key.strip()
        or len(idempotency_key) > OFFER_IDEMPOTENCY_KEY_MAX_LENGTH
    ):
        raise InvalidOfferIdempotencyKey()
    _validate_offer_values(order_amount, income_rate, quantity_available)
    payload, payee_vpa = _decode_pay_in_qr(pay_in_qr, order_amount)
    values = dict(
        order_amount=order_amount,
        income_rate=income_rate,
        quantity_available=quantity_available,
        is_active=bool(is_active),
        pay_in_qr_payload=payload,
        pay_in_upi_id=payee_vpa,
    )

    try:
        with transaction.atomic():
            _lock_user(admin)
            existing = OrderOffer.objects.filter(idempotency_key=idempotency_key).first()
            if existing is not None:
                return _replay_offer(existing, admin, values)

            offer = OrderOffer.objects.create(
                currency=Currency.INR,
                created_by=admin,
                idempotency_key=idempotency_key,
                **values,
            )
            record_audit_event(
                actor=admin,
                action=AuditEvent.Action.OFFER_CHANGED,
                target=offer,
                ip_address=ip_address,
                metadata={'change': 'created'},
            )
            return offer
    except IntegrityError:
        # A concurrent request with the same key won the race.
        existing = OrderOffer.objects.filter(idempotency_key=idempotency_key).first()
        if existing is not None:
            return _replay_offer(existing, admin, values)
        raise OfferCreateConflict()


def _validate_offer_values(order_amount, income_rate, quantity_available):
    if not isinstance(order_amount, Decimal) or order_amount <= 0:
        raise InvalidOffer('Order amount must be greater than zero.')
    if order_amount != order_amount.to_integral_value():
        raise InvalidOffer('Order amount must be in whole rupees.')
    if not isinstance(income_rate, Decimal) or not (Decimal('0') <= income_rate <= Decimal('100')):
        raise InvalidOffer('Income rate must be between 0 and 100.')
    if quantity_available is not None and (
        isinstance(quantity_available, bool)
        or not isinstance(quantity_available, int)
        or quantity_available < 1
    ):
        raise InvalidOffer('Quantity must be at least 1, or empty for no limit.')


def _decode_pay_in_qr(pay_in_qr, order_amount):
    """Return (payload, pa) for the uploaded Pay-In QR, or raise InvalidOffer."""
    if pay_in_qr is None:
        raise InvalidOffer('Pay-In QR is required.')
    try:
        payload = decode_upi_qr(pay_in_qr)
        params = dict(parse_upi_payload(payload))
    except UpiQrError as exc:
        raise InvalidOffer(str(exc))
    if 'am' in params:
        # A QR fixed to an amount is only usable if it is this offer's amount.
        try:
            fixed = Decimal(params['am'])
        except ArithmeticError:
            fixed = None
        if fixed is None or not fixed.is_finite() or fixed != order_amount:
            raise InvalidOffer('This QR is fixed to a different amount than the order amount.')
    return payload, params['pa']


def _replay_offer(offer, admin, values):
    if offer.created_by_id != admin.pk or any(
        getattr(offer, field) != value for field, value in values.items()
    ):
        raise OfferIdempotencyKeyConflict()
    return offer


# --- member reads -------------------------------------------------------------

@dataclass(frozen=True)
class OfferPreview:
    """
    A claimable offer as a member sees it before claiming. income_amount and
    payout_target come from orders.calculations - the same functions
    claim_order snapshots onto the Order - so the preview always matches
    what a claim made now would store.
    """

    id: int
    currency: str
    order_amount: Decimal
    income_rate: Decimal
    income_amount: Decimal
    payout_target: Decimal
    valid_to: datetime | None


def claimable_offers_filter(now):
    """
    The offer-side conditions _lock_claimable_offer enforces, as a filter:
    active, inside the valid window when set, and not sold out.
    """
    return (
        Q(is_active=True)
        & (Q(valid_from__isnull=True) | Q(valid_from__lte=now))
        & (Q(valid_to__isnull=True) | Q(valid_to__gt=now))
        & (Q(quantity_available__isnull=True) | Q(quantity_available__gt=0))
    )


def list_claimable_offers(*, user):
    """
    Return OfferPreviews for the offers `user` can claim right now, smallest
    order amount first (id breaks ties). Read-only. Whether the member has a
    payout method is checked at claim time, not here.
    """
    _require_reader(user)
    offers = OrderOffer.objects.filter(claimable_offers_filter(timezone.now())).order_by(
        'order_amount', 'id'
    )
    return [_preview(offer) for offer in offers]


def list_member_orders(*, user):
    """Return the member's own orders, newest first (id breaks ties). Read-only."""
    _require_reader(user)
    return (
        Order.objects.filter(user=user)
        .select_related('payout_method__provider')
        .order_by('-claimed_at', '-id')
    )


def get_member_order(*, user, order_id):
    """
    Return the member's own order `order_id`. Unknown ids and other members'
    orders both raise OrderNotFound. Read-only.
    """
    _require_reader(user)
    pk = _as_pk(order_id)
    order = (
        Order.objects.filter(pk=pk, user=user).select_related('payout_method__provider').first()
        if pk
        else None
    )
    if order is None:
        raise OrderNotFound()
    return order


def latest_pay_in(order):
    """The order's most recently submitted PayIn, or None."""
    return (
        order.pay_ins.select_related('buy_method__provider')
        .order_by('-submitted_at', '-id')
        .first()
    )


# --- admin reads --------------------------------------------------------------

def list_admin_orders(*, admin):
    """
    Return every member's orders for an active admin, newest first (id
    breaks ties). Read-only. Any active admin may review any order - the
    same rule as pay-in review and payouts - so there is no per-admin
    filter. The list carries no payout destination.
    """
    _require_admin_reader(admin)
    return Order.objects.select_related('user').order_by('-claimed_at', '-id')


def get_admin_order(*, admin, order_id, ip_address=None):
    """
    Return order `order_id` for an active admin; unknown ids raise
    OrderNotFound. Read-only for financial state. Because the detail
    reveals the member's UPI ID, each call records one PAYOUT_METHOD_VIEWED
    AuditEvent (identifiers only in metadata), as the payout-method view does,
    and - when the latest pay-in names the member's BUY method - one
    BUY_METHOD_VIEWED AuditEvent for that method.
    """
    _require_admin_reader(admin)
    pk = _as_pk(order_id)
    order = (
        Order.objects.filter(pk=pk).select_related('user', 'payout_method__provider').first()
        if pk
        else None
    )
    if order is None:
        raise OrderNotFound()

    record_audit_event(
        actor=admin,
        action=AuditEvent.Action.PAYOUT_METHOD_VIEWED,
        target=order.payout_method,
        ip_address=ip_address,
        metadata={'order_id': order.pk},
    )
    payin = latest_pay_in(order)
    if payin is not None and payin.buy_method is not None:
        record_audit_event(
            actor=admin,
            action=AuditEvent.Action.BUY_METHOD_VIEWED,
            target=payin.buy_method,
            ip_address=ip_address,
            metadata={'order_id': order.pk, 'payin_id': payin.pk},
        )
    return order


def _require_admin_reader(user):
    if not is_active_admin(user):
        raise AdminOrderReadPermissionDenied()


def _require_reader(user):
    if not is_verified_member(user):
        raise OrderReadPermissionDenied()


def _preview(offer):
    income_amount = calculate_income_amount(offer.order_amount, offer.income_rate)
    return OfferPreview(
        id=offer.pk,
        currency=offer.currency,
        order_amount=offer.order_amount,
        income_rate=offer.income_rate,
        income_amount=income_amount,
        payout_target=calculate_payout_target(offer.order_amount, income_amount),
        valid_to=offer.valid_to,
    )
