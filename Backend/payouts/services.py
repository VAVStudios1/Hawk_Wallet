"""
Admin payout services.

create_payout commits part (or all) of what the platform owes on an order
as a new PENDING Payout. Committing is not paying: payout_paid_total, the
outstanding balance and the member's money are untouched until the payout
is marked PAID by mark_payout_paid.

Locking order is always: the admin's User row (serializes that admin's
retries and key checks), then the Order row - the same User-then-Order
order as the pay-in services - then, when marking paid, the Payout row.
The Order row lock serializes every payout on an order, so concurrent
payouts cannot over-commit or be paid twice; the DB constraints
(payout_committed_total <= payout_target, payout_paid_total <=
payout_committed_total, one PAID payout per UTR) remain the final guard
(e.g. on SQLite, where row locks are a no-op).
"""
import mimetypes
import re
from dataclasses import dataclass
from decimal import Decimal

from django.contrib.auth import get_user_model
from django.db import IntegrityError, transaction
from django.utils import timezone

from audit.models import AuditEvent, LedgerEntry
from audit.services import record_audit_event, record_ledger_entry
from core.permissions import is_active_admin
from orders.models import Order
from payins.models import PayIn
from payouts.exceptions import (
    IdempotencyKeyConflict,
    InvalidCancellationReason,
    InvalidIdempotencyKey,
    InvalidPayoutAmount,
    InvalidPayoutUtr,
    OrderNotInPayout,
    OrderNotPayable,
    PayoutAlreadyCancelled,
    PayoutAlreadyPaid,
    PayoutConflict,
    PayoutExceedsRemaining,
    PayoutNotCancellable,
    PayoutNotFound,
    PayoutNotPending,
    PayoutOrderNotFound,
    PayoutPermissionDenied,
    PayoutQrNotFound,
    PayoutTotalConflict,
    PayoutUtrAlreadyUsed,
)
from payouts.models import Payout

User = get_user_model()

IDEMPOTENCY_KEY_MAX_LENGTH = Payout._meta.get_field('idempotency_key').max_length
PAYABLE_STATUSES = (Order.Status.ACTIVE, Order.Status.PAYOUT_IN_PROGRESS)
# Payout.amount is max_digits=12, decimal_places=2: whole rupees below 10^10.
MAX_PAYOUT_AMOUNT = Decimal('9999999999')
RUPEES = Decimal('1.00')
# Exactly 12 ASCII digits, matched with fullmatch: unlike the model's
# RegexValidator ('$' allows a trailing newline), nothing else gets through.
STRICT_UTR = re.compile(r'[0-9]{12}')
# A pay-in UTR in one of these states is a live claim on that bank transfer.
ACTIVE_PAYIN_STATUSES = (PayIn.Status.UNDER_REVIEW, PayIn.Status.CONFIRMED)
# Same limit as a pay-in rejection reason.
CANCELLATION_REASON_MAX_LENGTH = 500


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


def mark_payout_paid(*, admin, payout_id, utr, idempotency_key):
    """
    Mark PENDING payout `payout_id` as PAID with bank reference `utr` and
    return it.

    The order must be PAYOUT_IN_PROGRESS. utr must be exactly 12 ASCII
    digits as given (leading zeros kept) and must not back another PAID
    payout or an UNDER_REVIEW/CONFIRMED pay-in. Only payout_paid_total
    changes on the order (payout_committed_total already counts this
    payout); when it reaches payout_target exactly, the order becomes
    COMPLETED.

    Ledger: one PAYOUT_PAID entry whose balances are the order's outstanding
    payout (payout_target - payout_paid_total) before and after, plus one
    ORDER_COMPLETED entry, with empty balances, when the order completes.

    Idempotency: Payout has no field for a mark-paid key, so idempotency_key
    is validated but not stored, and replays are recognised from the
    payout's own state instead. Marking an already-PAID payout with the same
    UTR returns it unchanged; with a different UTR it raises
    PayoutAlreadyPaid. A retry is therefore not tied to its original key or
    admin - any active admin repeating the same UTR gets the replay.
    """
    if not is_active_admin(admin):
        raise PayoutPermissionDenied('Only active admins can mark payouts paid.')
    _validate_idempotency_key(idempotency_key)
    _validate_utr(utr)

    try:
        with transaction.atomic():
            _lock_user(admin)
            order, payout = _lock_order_and_payout(payout_id)

            if payout.status == Payout.Status.PAID:
                return _replay_paid(payout, utr)
            if payout.status != Payout.Status.PENDING:
                raise PayoutNotPending()
            if order.status != Order.Status.PAYOUT_IN_PROGRESS:
                raise OrderNotInPayout()
            if _utr_in_use(utr):
                raise PayoutUtrAlreadyUsed()

            old_paid_total = order.payout_paid_total
            new_paid_total = old_paid_total + payout.amount
            if (
                new_paid_total > order.payout_committed_total
                or order.payout_committed_total > order.payout_target
            ):
                raise PayoutTotalConflict()

            now = timezone.now()
            payout.status = Payout.Status.PAID
            payout.utr = utr
            payout.paid_by = admin
            payout.paid_at = now
            payout.save(update_fields=['status', 'utr', 'paid_by', 'paid_at', 'updated_at'])

            completed = new_paid_total == order.payout_target
            order.payout_paid_total = new_paid_total
            update_fields = ['payout_paid_total', 'updated_at']
            if completed:
                order.status = Order.Status.COMPLETED
                order.completed_at = now
                update_fields += ['status', 'completed_at']
            order.save(update_fields=update_fields)

            balance_before = order.payout_target - old_paid_total
            record_ledger_entry(
                actor=admin,
                action=LedgerEntry.Action.PAYOUT_PAID,
                order=order,
                payout=payout,
                amount=payout.amount,
                balance_before=balance_before,
                balance_after=balance_before - payout.amount,
                reference_id=order.reference,
            )
            if completed:
                # Completion moves no money (the PAYOUT_PAID entry did), so
                # like other non-balance events it carries empty balances.
                record_ledger_entry(
                    actor=admin,
                    action=LedgerEntry.Action.ORDER_COMPLETED,
                    order=order,
                    amount=order.payout_paid_total,
                    reference_id=order.reference,
                )
            return payout
    except IntegrityError:
        # A concurrent request paid this payout, or another PAID payout took
        # the UTR (unique constraint); everything this attempt did rolled back.
        payout = Payout.objects.filter(pk=_as_pk(payout_id)).first()
        if payout is not None and payout.status == Payout.Status.PAID:
            return _replay_paid(payout, utr)
        if _utr_in_use(utr):
            raise PayoutUtrAlreadyUsed()
        raise PayoutConflict()


def cancel_payout(*, admin, payout_id, cancellation_reason, idempotency_key):
    """
    Cancel PENDING payout `payout_id` (one created by mistake) and return it.

    PENDING -> CANCELLED: the payout's amount is released from the order's
    payout_committed_total, so it can be committed again by a new payout.
    payout_paid_total, the order status and the member's money are
    untouched. The order must be PAYOUT_IN_PROGRESS (every PENDING payout's
    order is). cancellation_reason must be non-blank and at most 500
    characters; it is stored as-is in Payout.note. A CANCELLED payout is
    frozen, like a PAID one.

    Ledger: one PAYOUT_CANCELLED entry for the released amount, with empty
    balances - what the platform owes (payout_target - payout_paid_total)
    does not change.

    Idempotency: as with mark_payout_paid, Payout has no field for the key,
    so idempotency_key is validated but not stored and replays are
    recognised from the payout's own state. Cancelling an already-CANCELLED
    payout with the same reason returns it unchanged; with a different
    reason it raises PayoutAlreadyCancelled. PAID and FAILED payouts raise
    PayoutNotCancellable.
    """
    if not is_active_admin(admin):
        raise PayoutPermissionDenied('Only active admins can cancel payouts.')
    _validate_idempotency_key(idempotency_key)
    _validate_cancellation_reason(cancellation_reason)

    try:
        with transaction.atomic():
            _lock_user(admin)
            order, payout = _lock_order_and_payout(payout_id)

            if payout.status == Payout.Status.CANCELLED:
                return _replay_cancelled(payout, cancellation_reason)
            if payout.status != Payout.Status.PENDING:
                raise PayoutNotCancellable()
            if order.status != Order.Status.PAYOUT_IN_PROGRESS:
                raise OrderNotInPayout(
                    'Payouts can only be cancelled while the order payout is in progress.'
                )
            new_committed_total = order.payout_committed_total - payout.amount
            if new_committed_total < order.payout_paid_total:
                # Only reachable if the order totals and its payouts disagree.
                raise PayoutTotalConflict(
                    'Cancelling this payout would leave less committed than already paid.'
                )

            payout.status = Payout.Status.CANCELLED
            payout.note = cancellation_reason
            payout.save(update_fields=['status', 'note', 'updated_at'])

            order.payout_committed_total = new_committed_total
            order.save(update_fields=['payout_committed_total', 'updated_at'])

            record_ledger_entry(
                actor=admin,
                action=LedgerEntry.Action.PAYOUT_CANCELLED,
                order=order,
                payout=payout,
                amount=payout.amount,
                reference_id=order.reference,
            )
            return payout
    except IntegrityError:
        # A concurrent request cancelled or paid this payout; everything
        # this attempt did rolled back.
        payout = Payout.objects.filter(pk=_as_pk(payout_id)).first()
        if payout is not None and payout.status == Payout.Status.CANCELLED:
            return _replay_cancelled(payout, cancellation_reason)
        raise PayoutConflict()


@dataclass(frozen=True)
class AdminPayoutMethodView:
    """What an admin needs to send one payout, and nothing else."""

    payout_id: int
    order_id: int
    order_reference: str
    user_id: int
    user_name: str
    amount: Decimal
    status: str
    provider_code: str
    provider_name: str
    upi_id: str
    # Stored name in private storage (no public URL); serve it only through
    # an authorized view.
    qr_image: str
    qr_sha256: str


def get_payout_method_for_admin(*, admin, payout_id, ip_address=None):
    """
    Return where payout `payout_id` must be sent, for an active admin.

    The destination is always the payout's own: provider and UPI ID come
    from the snapshot taken when the payout was created, and the QR from
    the payout method fixed on it (frozen once any order or payout uses
    it). The member's current default, newer or deactivated methods are
    never consulted. Works for a payout in any status.

    Read-only for financial state: no payout, order or ledger row changes.
    Because it reveals a member's UPI ID and QR, each call records one
    PAYOUT_METHOD_VIEWED AuditEvent (identifiers only in metadata).
    """
    if not is_active_admin(admin):
        raise PayoutPermissionDenied('Only active admins can view payout methods.')
    pk = _as_pk(payout_id)
    payout = (
        Payout.objects.select_related('order', 'user', 'payout_method').filter(pk=pk).first()
        if pk else None
    )
    if payout is None:
        raise PayoutNotFound()

    method = payout.payout_method
    record_audit_event(
        actor=admin,
        action=AuditEvent.Action.PAYOUT_METHOD_VIEWED,
        target=method,
        ip_address=ip_address,
        metadata={'payout_id': payout.pk, 'order_id': payout.order_id},
    )
    return AdminPayoutMethodView(
        payout_id=payout.pk,
        order_id=payout.order_id,
        order_reference=payout.order.reference,
        user_id=payout.user_id,
        user_name=payout.user.name,
        amount=payout.amount,
        status=payout.status,
        provider_code=payout.provider_code_snapshot,
        provider_name=payout.provider_name_snapshot,
        upi_id=payout.upi_id_snapshot,
        qr_image=method.qr_image.name,
        qr_sha256=method.qr_sha256,
    )


@dataclass(frozen=True)
class AdminPayoutQr:
    """An opened payout QR file; the caller must close `file`."""

    file: object
    content_type: str


def get_payout_qr_for_admin(*, admin, payout_id, ip_address=None):
    """
    Open the QR image of the payout method fixed on payout `payout_id`, for
    an active admin, and return it with its content type.

    The QR lives in private storage and has no URL; this is the only way to
    read it. Like get_payout_method_for_admin it uses the payout's own
    method (frozen once used) and works for a payout in any status. Each
    successful call records one PAYOUT_METHOD_QR_VIEWED AuditEvent
    (identifiers only in metadata). Read-only for financial state.
    """
    if not is_active_admin(admin):
        raise PayoutPermissionDenied('Only active admins can view payout methods.')
    pk = _as_pk(payout_id)
    payout = Payout.objects.select_related('payout_method').filter(pk=pk).first() if pk else None
    if payout is None:
        raise PayoutNotFound()

    method = payout.payout_method
    try:
        qr_file = method.qr_image.storage.open(method.qr_image.name, 'rb')
    except (FileNotFoundError, ValueError):
        raise PayoutQrNotFound()
    try:
        record_audit_event(
            actor=admin,
            action=AuditEvent.Action.PAYOUT_METHOD_QR_VIEWED,
            target=method,
            ip_address=ip_address,
            metadata={'payout_id': payout.pk, 'order_id': payout.order_id},
        )
    except BaseException:
        qr_file.close()
        raise
    content_type = mimetypes.guess_type(method.qr_image.name)[0] or 'application/octet-stream'
    return AdminPayoutQr(file=qr_file, content_type=content_type)


def _validate_utr(utr):
    if not isinstance(utr, str) or STRICT_UTR.fullmatch(utr) is None:
        raise InvalidPayoutUtr()


def _lock_order_and_payout(payout_id):
    pk = _as_pk(payout_id)
    # order_id is immutable, so reading it before taking any lock is safe;
    # this keeps the Order-then-Payout lock order.
    order_id = (
        Payout.objects.filter(pk=pk).values_list('order_id', flat=True).first()
        if pk else None
    )
    if order_id is None:
        raise PayoutNotFound()
    order = Order.objects.select_for_update().get(pk=order_id)
    payout = Payout.objects.select_for_update().get(pk=pk)
    return order, payout


def _replay_paid(payout, utr):
    if payout.utr != utr:
        raise PayoutAlreadyPaid()
    return payout


def _replay_cancelled(payout, cancellation_reason):
    if payout.note != cancellation_reason:
        raise PayoutAlreadyCancelled()
    return payout


def _validate_cancellation_reason(reason):
    if (
        not isinstance(reason, str)
        or not reason.strip()
        or len(reason) > CANCELLATION_REASON_MAX_LENGTH
    ):
        raise InvalidCancellationReason()


def _utr_in_use(utr):
    # The payout being marked is never PAID here, so it cannot match itself.
    return (
        Payout.objects.filter(utr=utr, status=Payout.Status.PAID).exists()
        or PayIn.objects.filter(utr=utr, status__in=ACTIVE_PAYIN_STATUSES).exists()
    )


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
