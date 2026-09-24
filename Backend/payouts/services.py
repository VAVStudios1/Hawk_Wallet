"""
Admin payout services.

create_payout commits part (or all) of what the platform owes on an order
as a new PENDING Payout. Committing is not paying: payout_paid_total, the
outstanding balance and the member's money are untouched until the payout
is marked PAID (a separate service).

Locking order is always: the admin's User row (serializes that admin's
retries and key checks), then the Order row - the same User-then-Order
order as the pay-in services. The Order row lock serializes every payout on
an order, so concurrent payouts cannot over-commit; the DB constraint
payout_committed_total <= payout_target remains the final guard (e.g. on
SQLite, where row locks are a no-op).
"""
from decimal import Decimal

from django.contrib.auth import get_user_model
from django.db import IntegrityError, transaction

from audit.models import LedgerEntry
from audit.services import record_ledger_entry
from core.permissions import is_active_admin
from orders.models import Order
from payins.models import PayIn
from payouts.exceptions import (
    IdempotencyKeyConflict,
    InvalidIdempotencyKey,
    InvalidPayoutAmount,
    OrderNotPayable,
    PayoutConflict,
    PayoutExceedsRemaining,
    PayoutOrderNotFound,
    PayoutPermissionDenied,
)
from payouts.models import Payout

User = get_user_model()

IDEMPOTENCY_KEY_MAX_LENGTH = Payout._meta.get_field('idempotency_key').max_length
PAYABLE_STATUSES = (Order.Status.ACTIVE, Order.Status.PAYOUT_IN_PROGRESS)
# Payout.amount is max_digits=12, decimal_places=2: whole rupees below 10^10.
MAX_PAYOUT_AMOUNT = Decimal('9999999999')
RUPEES = Decimal('1.00')


def create_payout(*, admin, order_id, amount, idempotency_key):
    """
    Create a PENDING payout of `amount` on order `order_id` and return it.

    The order must be ACTIVE (pay-in confirmed) or already
    PAYOUT_IN_PROGRESS; the first payout moves ACTIVE -> PAYOUT_IN_PROGRESS.
    amount must be a positive whole-rupee Decimal (or int) that fits in
    what is still uncommitted (payout_target - payout_committed_total);
    reaching payout_target exactly is allowed. The payout goes to the
    payout method fixed on the order - never the member's current default -
    and the model snapshots its destination.

    The PAYOUT_CREATED ledger entry records the committed amount with empty
    balances: nothing has been paid, so what the platform owes is unchanged.

    Retrying with the same idempotency_key returns the original payout
    without committing again. Reusing the key for a different order or
    amount, or by another admin, raises IdempotencyKeyConflict.
    """
    if not is_active_admin(admin):
        raise PayoutPermissionDenied()
    _validate_idempotency_key(idempotency_key)
    amount = _validate_amount(amount)

    try:
        with transaction.atomic():
            _lock_user(admin)

            existing = _existing_payout(idempotency_key)
            if existing is not None:
                return _replay(existing, admin, order_id, amount)

            order = _lock_order(order_id)
            if order.status not in PAYABLE_STATUSES:
                raise OrderNotPayable()
            if not order.pay_ins.filter(status=PayIn.Status.CONFIRMED).exists():
                # Only reachable if order and pay-in state ever disagree.
                raise PayoutConflict()
            if amount > order.payout_target - order.payout_committed_total:
                raise PayoutExceedsRemaining()

            payout = Payout.objects.create(
                order=order,
                user=order.user,
                payout_method=order.payout_method,
                amount=amount,
                status=Payout.Status.PENDING,
                created_by=admin,
                idempotency_key=idempotency_key,
            )
            order.payout_committed_total += amount
            order.status = Order.Status.PAYOUT_IN_PROGRESS
            order.save(update_fields=['payout_committed_total', 'status', 'updated_at'])
            record_ledger_entry(
                actor=admin,
                action=LedgerEntry.Action.PAYOUT_CREATED,
                order=order,
                payout=payout,
                amount=amount,
                reference_id=order.reference,
            )
            return payout
    except IntegrityError:
        # A concurrent request with the same key won, or the committed-total
        # constraint fired; everything this attempt did rolled back.
        existing = _existing_payout(idempotency_key)
        if existing is not None:
            return _replay(existing, admin, order_id, amount)
        raise PayoutConflict()


def _validate_idempotency_key(key):
    if not isinstance(key, str) or not key.strip() or len(key) > IDEMPOTENCY_KEY_MAX_LENGTH:
        raise InvalidIdempotencyKey()


def _validate_amount(amount):
    # Decimal or int only: floats (and bools, a subclass of int) are refused,
    # and strings are left to the API layer's DecimalField to parse.
    if isinstance(amount, bool) or not isinstance(amount, (Decimal, int)):
        raise InvalidPayoutAmount()
    amount = Decimal(amount)
    if (
        not amount.is_finite()
        or amount <= 0
        or amount != amount.to_integral_value()
        or amount > MAX_PAYOUT_AMOUNT
    ):
        raise InvalidPayoutAmount()
    return amount.quantize(RUPEES)


def _lock_user(user):
    User.objects.select_for_update().only('pk').get(pk=user.pk)


def _existing_payout(idempotency_key):
    # Payout.idempotency_key is unique across all payouts.
    return Payout.objects.filter(idempotency_key=idempotency_key).first()


def _replay(payout, admin, order_id, amount):
    if (
        payout.created_by_id != admin.pk
        or payout.order_id != _as_pk(order_id)
        or payout.amount != amount
    ):
        raise IdempotencyKeyConflict()
    return payout


def _lock_order(order_id):
    pk = _as_pk(order_id)
    order = Order.objects.select_for_update().filter(pk=pk).first() if pk else None
    if order is None:
        raise PayoutOrderNotFound()
    return order


def _as_pk(value):
    try:
        return int(value)
    except (TypeError, ValueError):
        return None
