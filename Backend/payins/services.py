"""
Pay-in services: member-facing intent and UTR submission, and the admin
review (confirm / reject).

get_pay_in_intent is read-only: it tells the member where and how much to
pay for one of their orders. It creates no PayIn, writes no ledger entry
and never changes the order, so it is safe to call any number of times.

submit_pay_in_utr records the member's claim that they paid: a new
UNDER_REVIEW PayIn, the order moved to PAYMENT_UNDER_REVIEW and a
PAYIN_SUBMITTED ledger entry. It proves nothing until an admin reviews it.

Locking order is always: member's User row, then the Order row - the same
User-first order as the claim and payout-method services, so the member's
retries are serialized and deadlocks are ruled out. The PayIn unique
constraints (one UNDER_REVIEW/CONFIRMED per order, active UTR unique,
(user, idempotency_key) unique) remain the final guard, e.g. on SQLite
where row locks are a no-op.

confirm_pay_in / reject_pay_in finalize an UNDER_REVIEW PayIn. Locking
order: the admin's User row (serializes that admin's retries and key
checks), then the Order row, then the PayIn row. Every writer of a PayIn
takes its Order row first, so a confirm and a reject racing on the same
PayIn are serialized and the loser sees it already reviewed.
"""
import re
from dataclasses import dataclass
from decimal import Decimal
from urllib.parse import quote, urlencode

from django.contrib.auth import get_user_model
from django.core import signing
from django.db import IntegrityError, transaction
from django.utils import timezone

from audit.models import LedgerEntry
from audit.services import record_ledger_entry
from core.permissions import is_active_admin, is_verified_member
from orders.models import Order
from paymethods.models import PaymentProvider, PlatformReceivingAccount, Rail
from payins.exceptions import (
    IdempotencyKeyConflict,
    InvalidIdempotencyKey,
    InvalidPayInIntent,
    InvalidRejectionReason,
    InvalidUtr,
    NoReceivingAccount,
    OrderNotPayable,
    PayerAppUnavailable,
    PayInAlreadyReviewed,
    PayInConflict,
    PayInNotFound,
    PayInOrderNotFound,
    PayInPermissionDenied,
    ReviewPermissionDenied,
    UtrAlreadySubmitted,
)
from payins.models import PayIn

User = get_user_model()

IDEMPOTENCY_KEY_MAX_LENGTH = PayIn._meta.get_field('idempotency_key').max_length
# ASCII digits only: \d would also accept other Unicode digits, and $ would
# allow a trailing newline. Matched with fullmatch, never after stripping.
STRICT_UTR = re.compile(r'[0-9]{12}')
ACTIVE_PAYIN_STATUSES = (PayIn.Status.UNDER_REVIEW, PayIn.Status.CONFIRMED)
REJECTION_REASON_MAX_LENGTH = 500

UPI_PAY_URI_PREFIX = 'upi://pay?'
UPI_NOTE_TEMPLATE = 'Hawk Wallet order {reference}'
PAISE = Decimal('0.01')
INTENT_TOKEN_SALT = 'payins.pay_in_intent'


@dataclass(frozen=True)
class PayInIntent:
    """
    What the member needs to pay for one order. Carries the platform's
    receiving details only - never the member's own payout method.
    """

    order_reference: str
    amount: Decimal
    currency: str
    receiving_account_id: int
    payee_vpa: str
    payee_name: str
    upi_uri: str
    intent_token: str


def get_pay_in_intent(*, user, order_id):
    """
    Return the PayInIntent for the member's own order `order_id`.

    The order must be PAYMENT_REQUIRED. The amount is the order's
    order_amount (what the member pays the platform) - never income_amount
    or payout_target. The receiving account is the active UPI account with
    the lowest priority (then lowest id) whose provider is active and
    enabled for pay-ins, so repeated calls return the same intent while the
    platform configuration is unchanged.
    """
    if not is_verified_member(user):
        raise PayInPermissionDenied()

    order = _own_order(user, order_id)
    if order.status != Order.Status.PAYMENT_REQUIRED:
        raise OrderNotPayable()

    account = select_receiving_account()
    amount = _payment_amount(order)
    return PayInIntent(
        order_reference=order.reference,
        amount=amount,
        currency=order.currency,
        receiving_account_id=account.pk,
        payee_vpa=account.payee_vpa,
        payee_name=account.payee_name,
        upi_uri=build_upi_pay_uri(
            account=account,
            amount=amount,
            currency=order.currency,
            reference=order.reference,
        ),
        intent_token=_sign_intent(user, order, account),
    )


def select_receiving_account():
    account = (
        PlatformReceivingAccount.objects.select_related('provider')
        .filter(
            is_active=True,
            provider__is_active=True,
            provider__enabled_for_pay_in=True,
            provider__rail=Rail.UPI,
        )
        .order_by('priority', 'id')
        .first()
    )
    if account is None:
        raise NoReceivingAccount()
    return account


def build_upi_pay_uri(*, account, amount, currency, reference):
    """
    Build a upi://pay URI. `reference` (the order's public HW- reference) is
    sent as tr and inside the readable tn note, so admins can match the
    payment to its order. Every value is percent-encoded (spaces as %20,
    not '+', which UPI apps would show literally); '@' is left as-is since
    it is legal in a query and some UPI apps reject a VPA written as %40.
    """
    if not isinstance(amount, Decimal):
        raise TypeError('amount must be a Decimal.')
    params = [('pa', account.payee_vpa), ('pn', account.payee_name)]
    if account.merchant_code:
        params.append(('mc', account.merchant_code))
    params += [
        ('tr', reference),
        ('am', _format_amount(amount)),
        ('cu', currency),
        ('tn', UPI_NOTE_TEMPLATE.format(reference=reference)),
    ]
    return UPI_PAY_URI_PREFIX + urlencode(params, quote_via=quote, safe='@')


def _own_order(user, order_id):
    pk = _as_pk(order_id)
    order = Order.objects.filter(pk=pk, user=user).first() if pk else None
    if order is None:
        raise PayInOrderNotFound()
    return order


def _payment_amount(order):
    amount = order.order_amount
    # The DB guarantees a positive whole-rupee amount; refuse to hand out
    # payment instructions if that ever fails to hold.
    if not isinstance(amount, Decimal) or amount <= 0 or amount != amount.quantize(PAISE):
        raise OrderNotPayable()
    return amount


def _format_amount(amount):
    # Decimal formatting, never float: 255 -> '255.00'.
    return f'{amount.quantize(PAISE):f}'


def _as_pk(value):
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def submit_pay_in_utr(*, user, order_id, utr, idempotency_key, intent_token, payer_app=None):
    """
    Record that `user` paid for their order `order_id` with bank reference
    `utr`, and return the new UNDER_REVIEW PayIn.

    The order must be PAYMENT_REQUIRED; it moves to PAYMENT_UNDER_REVIEW.
    The amount is always the order's order_amount - the caller cannot set
    it. The UTR must be exactly 12 ASCII digits as submitted; nothing is
    stripped or normalized. intent_token is the token from the member's
    PayInIntent for this order: the PayIn records the receiving account that
    intent showed, never a newly selected one. payer_app (optional) is the id of a
    PaymentProvider enabled for pay-ins, recorded for information only.
    Earlier REJECTED PayIns are left untouched; a resubmission is a new row.

    Retrying with the same idempotency_key returns the original PayIn
    without writing anything again - even if its status has since changed.
    Reusing the key for a different order, UTR, intent account or explicit
    payer_app raises IdempotencyKeyConflict.
    """
    if not is_verified_member(user):
        raise PayInPermissionDenied()
    _validate_idempotency_key(idempotency_key)
    _validate_utr(utr)
    account_id = _intent_account_id(intent_token, user, order_id)

    try:
        with transaction.atomic():
            _lock_user(user)

            existing = _existing_submission(user, idempotency_key)
            if existing is not None:
                return _replay(existing, order_id, utr, payer_app, account_id)

            order = _lock_own_order(user, order_id)
            if order.status != Order.Status.PAYMENT_REQUIRED:
                raise OrderNotPayable()
            if order.pay_ins.filter(status__in=ACTIVE_PAYIN_STATUSES).exists():
                # Only reachable if status and pay-ins ever disagree.
                raise OrderNotPayable()
            if _utr_in_use(utr):
                raise UtrAlreadySubmitted()

            app = _resolve_payer_app(payer_app)
            account = _intent_account(account_id)
            amount = _payment_amount(order)

            payin = PayIn.objects.create(
                order=order,
                user=order.user,
                amount=amount,
                receiving_account=account,
                payee_vpa_snapshot=account.payee_vpa,
                payer_app=app,
                utr=utr,
                status=PayIn.Status.UNDER_REVIEW,
                idempotency_key=idempotency_key,
            )
            order.status = Order.Status.PAYMENT_UNDER_REVIEW
            order.save(update_fields=['status', 'updated_at'])
            # Unconfirmed money owes the member nothing yet, so the entry
            # records the amount without an outstanding balance.
            record_ledger_entry(
                actor=user,
                action=LedgerEntry.Action.PAYIN_SUBMITTED,
                order=order,
                payin=payin,
                amount=payin.amount,
                reference_id=order.reference,
            )
            return payin
    except IntegrityError:
        # A concurrent request won a unique constraint; everything this
        # attempt did rolled back.
        existing = _existing_submission(user, idempotency_key)
        if existing is not None:
            return _replay(existing, order_id, utr, payer_app, account_id)
        if _utr_in_use(utr):
            raise UtrAlreadySubmitted()
        raise PayInConflict()


def _sign_intent(user, order, account):
    # Signed with SECRET_KEY: the client can carry it but not alter it, so
    # it can only name an account the server itself chose for this order.
    return signing.dumps(
        {'u': user.pk, 'o': order.pk, 'a': account.pk}, salt=INTENT_TOKEN_SALT
    )


def _intent_account_id(intent_token, user, order_id):
    if not isinstance(intent_token, str) or not intent_token:
        raise InvalidPayInIntent()
    try:
        payload = signing.loads(intent_token, salt=INTENT_TOKEN_SALT)
    except signing.BadSignature:
        raise InvalidPayInIntent()
    if (
        not isinstance(payload, dict)
        or not isinstance(payload.get('o'), int)
        or payload.get('u') != user.pk
        or payload.get('o') != _as_pk(order_id)
        or not isinstance(payload.get('a'), int)
    ):
        raise InvalidPayInIntent()
    return payload['a']


def _intent_account(account_id):
    # The member already paid this account, so it is honored even if it has
    # been deactivated since the intent was shown.
    account = PlatformReceivingAccount.objects.filter(pk=account_id).first()
    if account is None:
        raise InvalidPayInIntent()
    return account


def _validate_idempotency_key(key):
    if not isinstance(key, str) or not key.strip() or len(key) > IDEMPOTENCY_KEY_MAX_LENGTH:
        raise InvalidIdempotencyKey()


def _validate_utr(utr):
    if not isinstance(utr, str) or STRICT_UTR.fullmatch(utr) is None:
        raise InvalidUtr()


def _lock_user(user):
    User.objects.select_for_update().only('pk').get(pk=user.pk)


def _existing_submission(user, idempotency_key):
    return PayIn.objects.filter(user=user, idempotency_key=idempotency_key).first()


def _replay(payin, order_id, utr, payer_app, account_id):
    if (
        payin.order_id != _as_pk(order_id)
        or payin.utr != utr
        or payin.receiving_account_id != account_id
    ):
        raise IdempotencyKeyConflict()
    if payer_app is not None and payin.payer_app_id != _as_pk(payer_app):
        raise IdempotencyKeyConflict()
    return payin


def _lock_own_order(user, order_id):
    pk = _as_pk(order_id)
    order = Order.objects.select_for_update().filter(pk=pk, user=user).first() if pk else None
    if order is None:
        raise PayInOrderNotFound()
    return order


def _utr_in_use(utr):
    return PayIn.objects.filter(utr=utr, status__in=ACTIVE_PAYIN_STATUSES).exists()


def _resolve_payer_app(payer_app):
    if payer_app is None:
        return None
    pk = _as_pk(payer_app)
    app = PaymentProvider.objects.filter(
        pk=pk, is_active=True, enabled_for_pay_in=True, rail=Rail.UPI
    ).first() if pk else None
    if app is None:
        raise PayerAppUnavailable()
    return app


# --- admin review -------------------------------------------------------------

def confirm_pay_in(*, admin, payin_id, idempotency_key):
    """
    Confirm the UNDER_REVIEW PayIn `payin_id` and return it (CONFIRMED).

    The member's payment of order_amount is accepted: the order moves
    PAYMENT_UNDER_REVIEW -> ACTIVE (eligible for payouts) and the platform
    now owes payout_target. No payout is created and the order is not
    completed. The PAYIN_CONFIRMED ledger entry records amount=order_amount
    and the outstanding payout going from 0 to payout_target.

    Retrying with the same idempotency_key returns the same PayIn without
    writing again. Reusing the key for another PayIn or for a reject raises
    IdempotencyKeyConflict.
    """
    return _review(
        admin=admin,
        payin_id=payin_id,
        idempotency_key=idempotency_key,
        outcome=PayIn.Status.CONFIRMED,
    )


def reject_pay_in(*, admin, payin_id, rejection_reason, idempotency_key):
    """
    Reject the UNDER_REVIEW PayIn `payin_id` and return it (REJECTED).

    The PayIn stays on record, frozen, UTR untouched. The order moves
    PAYMENT_UNDER_REVIEW -> PAYMENT_REQUIRED so the member can submit a new
    UTR (a new PayIn). Nothing is owed: the PAYIN_REJECTED ledger entry
    records the claimed amount with empty balances, and no money totals
    change. rejection_reason must be non-blank and at most 500 characters;
    it is stored as given.

    Retrying with the same idempotency_key and reason returns the same
    PayIn without writing again. Reusing the key for another PayIn, a
    confirm or a different reason raises IdempotencyKeyConflict.
    """
    _validate_rejection_reason(rejection_reason)
    return _review(
        admin=admin,
        payin_id=payin_id,
        idempotency_key=idempotency_key,
        outcome=PayIn.Status.REJECTED,
        rejection_reason=rejection_reason,
    )


def _review(*, admin, payin_id, idempotency_key, outcome, rejection_reason=''):
    if not is_active_admin(admin):
        raise ReviewPermissionDenied()
    _validate_idempotency_key(idempotency_key)

    try:
        with transaction.atomic():
            _lock_user(admin)

            existing = _existing_review(admin, idempotency_key)
            if existing is not None:
                return _replay_review(existing, payin_id, outcome, rejection_reason)

            payin, order = _lock_payin_and_order(payin_id)
            if payin.status != PayIn.Status.UNDER_REVIEW:
                raise PayInAlreadyReviewed()
            if (
                order.status != Order.Status.PAYMENT_UNDER_REVIEW
                or payin.amount != order.order_amount
            ):
                # Only reachable if order and pay-in state ever disagree.
                raise PayInConflict()

            now = timezone.now()
            payin.status = outcome
            payin.reviewed_by = admin
            payin.reviewed_at = now
            payin.review_idempotency_key = idempotency_key
            payin.rejection_reason = rejection_reason
            payin.save(update_fields=[
                'status', 'reviewed_by', 'reviewed_at', 'review_idempotency_key',
                'rejection_reason', 'updated_at',
            ])

            if outcome == PayIn.Status.CONFIRMED:
                _apply_confirmation(admin, payin, order, now)
            else:
                _apply_rejection(admin, payin, order)
            return payin
    except IntegrityError:
        # A concurrent request with the same key won; this attempt rolled back.
        existing = _existing_review(admin, idempotency_key)
        if existing is not None:
            return _replay_review(existing, payin_id, outcome, rejection_reason)
        raise PayInConflict()


def _apply_confirmation(admin, payin, order, now):
    outstanding_before = Decimal('0')
    order.status = Order.Status.ACTIVE
    order.activated_at = now
    order.save(update_fields=['status', 'activated_at', 'updated_at'])
    record_ledger_entry(
        actor=admin,
        action=LedgerEntry.Action.PAYIN_CONFIRMED,
        order=order,
        payin=payin,
        amount=order.order_amount,
        balance_before=outstanding_before,
        balance_after=order.payout_target - order.payout_paid_total,
        reference_id=order.reference,
    )


def _apply_rejection(admin, payin, order):
    order.status = Order.Status.PAYMENT_REQUIRED
    order.save(update_fields=['status', 'updated_at'])
    # Nothing was received, so nothing is owed: no balances.
    record_ledger_entry(
        actor=admin,
        action=LedgerEntry.Action.PAYIN_REJECTED,
        order=order,
        payin=payin,
        amount=payin.amount,
        reference_id=order.reference,
    )


def _validate_rejection_reason(reason):
    if (
        not isinstance(reason, str)
        or not reason.strip()
        or len(reason) > REJECTION_REASON_MAX_LENGTH
    ):
        raise InvalidRejectionReason()


def _existing_review(admin, idempotency_key):
    return PayIn.objects.filter(
        reviewed_by=admin, review_idempotency_key=idempotency_key
    ).first()


def _replay_review(payin, payin_id, outcome, rejection_reason):
    if (
        payin.pk != _as_pk(payin_id)
        or payin.status != outcome
        or payin.rejection_reason != rejection_reason
    ):
        raise IdempotencyKeyConflict()
    return payin


def _lock_payin_and_order(payin_id):
    pk = _as_pk(payin_id)
    # order_id never changes, so it is safe to read before locking.
    order_id = PayIn.objects.filter(pk=pk).values_list('order_id', flat=True).first() if pk else None
    if order_id is None:
        raise PayInNotFound()
    order = Order.objects.select_for_update().get(pk=order_id)
    payin = PayIn.objects.select_for_update().get(pk=pk)
    return payin, order
