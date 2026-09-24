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
from django.contrib.auth import get_user_model
from django.db import IntegrityError, transaction
from django.db.models import F
from django.utils import timezone

from audit.models import LedgerEntry
from audit.services import record_ledger_entry
from core.permissions import is_verified_member
from orders.calculations import calculate_income_amount, calculate_payout_target
from orders.exceptions import (
    ClaimConflict,
    ClaimPermissionDenied,
    IdempotencyKeyConflict,
    InvalidIdempotencyKey,
    NoActivePayoutMethod,
    OfferNotFound,
    OfferSoldOut,
    OfferUnavailable,
    PayoutMethodUnavailable,
)
from orders.models import Order, OrderOffer
from paymethods.models import UserPayoutMethod

User = get_user_model()

IDEMPOTENCY_KEY_MAX_LENGTH = Order._meta.get_field('idempotency_key').max_length


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
