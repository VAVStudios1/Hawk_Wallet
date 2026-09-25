import threading
from decimal import Decimal

import pytest
from django.contrib.auth.models import AnonymousUser
from django.db import connection
from django.utils import timezone

from audit.models import AuditEvent, LedgerEntry
from conftest import next_key
from core.models import User
from orders.models import Order
from payins.models import PayIn
from payins.services import confirm_pay_in, get_pay_in_intent, submit_pay_in_utr
from paymethods.services import deactivate_payout_method, set_default_payout_method
from payouts.exceptions import (
    IdempotencyKeyConflict,
    InvalidIdempotencyKey,
    InvalidPayoutAmount,
    InvalidPayoutUtr,
    OrderNotInPayout,
    OrderNotPayable,
    PayoutAlreadyPaid,
    PayoutConflict,
    PayoutExceedsRemaining,
    PayoutNotFound,
    PayoutNotPending,
    PayoutOrderNotFound,
    PayoutPermissionDenied,
    PayoutTotalConflict,
    PayoutUtrAlreadyUsed,
)
from payouts import services
from payouts.models import Payout
from paymethods.models import PaymentProvider
from payouts.services import (
    AdminPayoutMethodView,
    create_payout,
    get_payout_method_for_admin,
    mark_payout_paid,
)

SNAPSHOT_FIELDS = (
    'order_amount', 'income_rate', 'income_amount', 'payout_target',
    'payout_method_id', 'currency', 'reference', 'payout_paid_total',
)


@pytest.fixture
def member(make_user, make_method):
    user = make_user()
    make_method(user, 'member@ybl', is_default=True)
    return user


@pytest.fixture
def super_admin(make_user):
    return make_user(role=User.Role.SUPER_ADMIN)


@pytest.fixture
def active_order(member, make_order, receiving_account, admin_user):
    """An order whose pay-in was confirmed through the real services (ACTIVE)."""
    order = make_order(member)
    intent = get_pay_in_intent(user=member, order_id=order.pk)
    payin = submit_pay_in_utr(
        user=member, order_id=order.pk, utr='555555555555',
        idempotency_key=next_key(), intent_token=intent.intent_token,
    )
    confirm_pay_in(admin=admin_user, payin_id=payin.pk, idempotency_key=next_key())
    order.refresh_from_db()
    assert order.status == Order.Status.ACTIVE
    return order


def _pay(admin, order, amount, key=None):
    return create_payout(
        admin=admin, order_id=order.pk, amount=amount, idempotency_key=key or next_key()
    )


def _snapshot(order):
    values = Order.objects.values().get(pk=order.pk)
    return {field: values[field] for field in SNAPSHOT_FIELDS}


def _created_ledger(order=None):
    entries = LedgerEntry.objects.filter(action=LedgerEntry.Action.PAYOUT_CREATED)
    return entries.filter(order=order) if order else entries


def _assert_nothing_committed(order, status=Order.Status.ACTIVE):
    order = Order.objects.get(pk=order.pk)
    assert not Payout.objects.exists()
    assert not _created_ledger().exists()
    assert order.payout_committed_total == Decimal('0')
    assert order.status == status


# --- A. successful creation ---------------------------------------------------

@pytest.mark.parametrize('creator', ['admin_user', 'super_admin'])
def test_admin_creates_pending_payout(request, active_order, member, creator):
    admin = request.getfixturevalue(creator)
    payout = _pay(admin, active_order, Decimal('100'), key='payout-1')

    payout.refresh_from_db()
    assert payout.status == Payout.Status.PENDING
    assert payout.amount == Decimal('100.00')
    assert payout.order == active_order
    assert payout.user == member
    assert payout.created_by == admin
    assert payout.idempotency_key == 'payout-1'
    assert payout.utr == ''
    assert payout.paid_by is None and payout.paid_at is None


def test_first_payout_moves_order_to_payout_in_progress(admin_user, active_order):
    _pay(admin_user, active_order, Decimal('100'))
    active_order.refresh_from_db()
    assert active_order.status == Order.Status.PAYOUT_IN_PROGRESS
    assert active_order.completed_at is None


def test_committed_total_increases_paid_total_does_not(admin_user, active_order):
    _pay(admin_user, active_order, Decimal('100'))
    active_order.refresh_from_db()
    assert active_order.payout_committed_total == Decimal('100')
    assert active_order.payout_paid_total == Decimal('0')


def test_multiple_payouts_accumulate(admin_user, super_admin, active_order):
    _pay(admin_user, active_order, Decimal('100'))
    _pay(super_admin, active_order, Decimal('60'))
    _pay(admin_user, active_order, 5)

    active_order.refresh_from_db()
    assert active_order.payouts.count() == 3
    assert active_order.payout_committed_total == Decimal('165')
    assert active_order.payout_paid_total == Decimal('0')
    assert active_order.status == Order.Status.PAYOUT_IN_PROGRESS
    assert _created_ledger(active_order).count() == 3


def test_exact_remaining_target_allowed(admin_user, active_order):
    _pay(admin_user, active_order, Decimal('200'))
    _pay(admin_user, active_order, Decimal('65'))

    active_order.refresh_from_db()
    assert active_order.payout_committed_total == active_order.payout_target == Decimal('265')
    # Fully committed is not completed: nothing has been paid yet.
    assert active_order.status == Order.Status.PAYOUT_IN_PROGRESS


def test_single_payout_of_full_target_allowed(admin_user, active_order):
    payout = _pay(admin_user, active_order, active_order.payout_target)
    assert payout.amount == Decimal('265')


def test_order_financial_snapshot_unchanged(admin_user, active_order):
    before = _snapshot(active_order)
    _pay(admin_user, active_order, Decimal('100'))
    assert _snapshot(active_order) == before


def test_no_payout_is_marked_paid(admin_user, active_order):
    _pay(admin_user, active_order, Decimal('100'))
    _pay(admin_user, active_order, Decimal('165'))

    assert not Payout.objects.exclude(status=Payout.Status.PENDING).exists()
    assert not LedgerEntry.objects.filter(action=LedgerEntry.Action.PAYOUT_PAID).exists()
    assert not LedgerEntry.objects.filter(action=LedgerEntry.Action.ORDER_COMPLETED).exists()


def test_payout_created_ledger_entry(admin_user, active_order):
    payout = _pay(admin_user, active_order, Decimal('100'))

    entry = _created_ledger().get()
    assert entry.actor == admin_user
    assert entry.order == active_order
    assert entry.payout == payout
    assert entry.payin is None
    assert entry.amount == Decimal('100')
    # Committing pays nothing, so what the platform owes is unchanged.
    assert entry.balance_before is None and entry.balance_after is None
    assert entry.reference_id == active_order.reference


# --- B. payout method and snapshot --------------------------------------------

def test_payout_uses_order_payout_method(admin_user, active_order):
    payout = _pay(admin_user, active_order, Decimal('100'))
    assert payout.payout_method_id == active_order.payout_method_id


def test_payout_method_snapshot(admin_user, active_order):
    payout = _pay(admin_user, active_order, Decimal('100'))
    payout.refresh_from_db()
    assert payout.upi_id_snapshot == 'member@ybl'
    assert payout.provider_code_snapshot == 'PHONEPE'
    assert payout.provider_name_snapshot == 'PhonePe'


def test_new_default_method_does_not_redirect_payout(admin_user, member, make_method, active_order):
    original = active_order.payout_method
    newer = make_method(member, 'newer@ybl')
    set_default_payout_method(user=member, method_id=newer.pk)

    payout = _pay(admin_user, active_order, Decimal('100'))

    assert payout.payout_method == original
    assert payout.upi_id_snapshot == 'member@ybl'
    assert Order.objects.get(pk=active_order.pk).payout_method == original


def test_deactivated_order_method_still_receives_payout(admin_user, member, make_method, active_order):
    # The method is fixed on the order for good; deactivation only stops new use.
    original = active_order.payout_method
    newer = make_method(member, 'newer@ybl')
    set_default_payout_method(user=member, method_id=newer.pk)
    deactivate_payout_method(user=member, method_id=original.pk)

    payout = _pay(admin_user, active_order, Decimal('100'))
    assert payout.payout_method == original
    assert payout.upi_id_snapshot == 'member@ybl'


# --- C. permissions -----------------------------------------------------------

@pytest.mark.parametrize('extra', [
    {},                                                 # member
    {'role': User.Role.ADMIN, 'is_active': False},
    {'role': User.Role.SUPER_ADMIN, 'is_active': False},
])
def test_non_admin_or_inactive_admin_cannot_create(make_user, active_order, extra):
    with pytest.raises(PayoutPermissionDenied):
        _pay(make_user(**extra), active_order, Decimal('100'))
    _assert_nothing_committed(active_order)


def test_order_owner_cannot_create_payout(member, active_order):
    with pytest.raises(PayoutPermissionDenied):
        _pay(member, active_order, Decimal('100'))
    _assert_nothing_committed(active_order)


def test_anonymous_cannot_create(active_order):
    with pytest.raises(PayoutPermissionDenied):
        _pay(AnonymousUser(), active_order, Decimal('100'))
    _assert_nothing_committed(active_order)


@pytest.mark.parametrize('order_id', [999999, 'abc', None])
def test_unknown_order(admin_user, active_order, order_id):
    with pytest.raises(PayoutOrderNotFound):
        create_payout(
            admin=admin_user, order_id=order_id, amount=Decimal('100'),
            idempotency_key=next_key(),
        )
    _assert_nothing_committed(active_order)


# --- D. order status ----------------------------------------------------------

@pytest.mark.parametrize('status', [
    Order.Status.PAYMENT_REQUIRED,
    Order.Status.PAYMENT_UNDER_REVIEW,
    Order.Status.EXPIRED,
    Order.Status.CANCELLED,
])
def test_order_without_confirmed_payment_rejected(admin_user, member, make_order, status):
    order = make_order(member)
    Order.objects.filter(pk=order.pk).update(status=status)

    with pytest.raises(OrderNotPayable):
        _pay(admin_user, order, Decimal('100'))
    _assert_nothing_committed(order, status=status)


def test_payment_under_review_order_rejected(admin_user, member, make_order, receiving_account):
    order = make_order(member)
    intent = get_pay_in_intent(user=member, order_id=order.pk)
    submit_pay_in_utr(
        user=member, order_id=order.pk, utr='555555555555',
        idempotency_key=next_key(), intent_token=intent.intent_token,
    )

    with pytest.raises(OrderNotPayable):
        _pay(admin_user, order, Decimal('100'))
    _assert_nothing_committed(order, status=Order.Status.PAYMENT_UNDER_REVIEW)


def test_completed_order_rejected(admin_user, active_order):
    Order.objects.filter(pk=active_order.pk).update(
        status=Order.Status.COMPLETED,
        payout_committed_total=active_order.payout_target,
        payout_paid_total=active_order.payout_target,
    )
    with pytest.raises(OrderNotPayable):
        _pay(admin_user, active_order, Decimal('1'))
    assert not Payout.objects.exists()


def test_active_order_without_confirmed_payin_refused(admin_user, member, make_order):
    # Inconsistent state guard: ACTIVE must be backed by a confirmed pay-in.
    order = make_order(member)
    Order.objects.filter(pk=order.pk).update(status=Order.Status.ACTIVE)

    with pytest.raises(PayoutConflict):
        _pay(admin_user, order, Decimal('100'))
    assert not Payout.objects.exists()


# --- E. amount ----------------------------------------------------------------

@pytest.mark.parametrize('amount', [
    Decimal('0'),
    Decimal('-1'),
    Decimal('100.50'),
    Decimal('0.01'),
    Decimal('NaN'),
    Decimal('Infinity'),
    Decimal('10000000000'),
    0,
    -5,
    100.0,          # float
    True,
    '100',          # strings are parsed by the API layer, not here
    None,
])
def test_invalid_amount_rejected(admin_user, active_order, amount):
    with pytest.raises(InvalidPayoutAmount):
        _pay(admin_user, active_order, amount)
    _assert_nothing_committed(active_order)


def test_whole_amount_with_zero_paise_accepted(admin_user, active_order):
    assert _pay(admin_user, active_order, Decimal('100.00')).amount == Decimal('100')


def test_payout_above_target_rejected(admin_user, active_order):
    with pytest.raises(PayoutExceedsRemaining):
        _pay(admin_user, active_order, Decimal('266'))
    _assert_nothing_committed(active_order)


def test_payout_above_remaining_rejected(admin_user, active_order):
    _pay(admin_user, active_order, Decimal('200'))

    with pytest.raises(PayoutExceedsRemaining):
        _pay(admin_user, active_order, Decimal('66'))

    active_order.refresh_from_db()
    assert active_order.payout_committed_total == Decimal('200')
    assert active_order.payouts.count() == 1
    assert _created_ledger().count() == 1


def test_fully_committed_order_rejects_more(admin_user, active_order):
    _pay(admin_user, active_order, active_order.payout_target)
    with pytest.raises(PayoutExceedsRemaining):
        _pay(admin_user, active_order, Decimal('1'))


# --- F. idempotency -----------------------------------------------------------

def test_replay_returns_existing_payout(admin_user, active_order):
    first = _pay(admin_user, active_order, Decimal('100'), key='same')
    order_before = Order.objects.values().get(pk=active_order.pk)

    again = _pay(admin_user, active_order, Decimal('100.00'), key='same')

    assert again.pk == first.pk
    assert Payout.objects.count() == 1
    assert _created_ledger().count() == 1
    assert Order.objects.values().get(pk=active_order.pk) == order_before


def test_replay_after_order_fully_committed(admin_user, active_order):
    first = _pay(admin_user, active_order, active_order.payout_target, key='same')
    assert _pay(admin_user, active_order, active_order.payout_target, key='same').pk == first.pk


def test_key_reused_with_different_amount_conflicts(admin_user, active_order):
    _pay(admin_user, active_order, Decimal('100'), key='same')
    with pytest.raises(IdempotencyKeyConflict):
        _pay(admin_user, active_order, Decimal('50'), key='same')
    assert Order.objects.get(pk=active_order.pk).payout_committed_total == Decimal('100')


def test_key_reused_for_different_order_conflicts(admin_user, active_order, member, make_order):
    _pay(admin_user, active_order, Decimal('100'), key='same')
    other = make_order(member)
    with pytest.raises(IdempotencyKeyConflict):
        _pay(admin_user, other, Decimal('100'), key='same')
    assert Payout.objects.count() == 1


def test_key_reused_by_another_admin_conflicts(admin_user, super_admin, active_order):
    _pay(admin_user, active_order, Decimal('100'), key='same')
    with pytest.raises(IdempotencyKeyConflict):
        _pay(super_admin, active_order, Decimal('100'), key='same')
    assert Payout.objects.count() == 1


@pytest.mark.parametrize('key', ['', '   ', None, 'x' * 65])
def test_invalid_idempotency_key(admin_user, active_order, key):
    with pytest.raises(InvalidIdempotencyKey):
        create_payout(
            admin=admin_user, order_id=active_order.pk, amount=Decimal('100'),
            idempotency_key=key,
        )
    _assert_nothing_committed(active_order)


# --- G. concurrency (PostgreSQL only) -----------------------------------------

postgres_only = pytest.mark.skipif(
    connection.vendor != 'postgresql',
    reason='Row locks (select_for_update) are a no-op on SQLite.',
)


def _run_concurrently(*calls):
    barrier = threading.Barrier(len(calls))
    results = [None] * len(calls)

    def worker(index, call):
        try:
            barrier.wait()
            results[index] = call()
        except Exception as exc:
            results[index] = exc
        finally:
            connection.close()

    threads = [threading.Thread(target=worker, args=(i, c)) for i, c in enumerate(calls)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    return results


@postgres_only
@pytest.mark.django_db(transaction=True)
def test_concurrent_payouts_cannot_overcommit(admin_user, super_admin, active_order):
    # 200 + 200 > 265: only one may commit.
    results = _run_concurrently(
        lambda: _pay(admin_user, active_order, Decimal('200')),
        lambda: _pay(super_admin, active_order, Decimal('200')),
    )

    created = [r for r in results if isinstance(r, Payout)]
    errors = [r for r in results if not isinstance(r, Payout)]
    assert len(created) == 1
    assert len(errors) == 1 and isinstance(errors[0], PayoutExceedsRemaining)
    active_order.refresh_from_db()
    assert active_order.payout_committed_total == Decimal('200')
    assert _created_ledger().count() == 1


@postgres_only
@pytest.mark.django_db(transaction=True)
def test_concurrent_payouts_that_fit_all_commit(admin_user, super_admin, active_order):
    results = _run_concurrently(
        lambda: _pay(admin_user, active_order, Decimal('100')),
        lambda: _pay(super_admin, active_order, Decimal('165')),
    )

    assert all(isinstance(r, Payout) for r in results), results
    active_order.refresh_from_db()
    assert active_order.payout_committed_total == active_order.payout_target


@postgres_only
@pytest.mark.django_db(transaction=True)
def test_concurrent_retries_with_same_key_create_one_payout(admin_user, active_order):
    results = _run_concurrently(
        *[lambda: _pay(admin_user, active_order, Decimal('100'), key='same') for _ in range(3)]
    )

    assert all(isinstance(r, Payout) for r in results), results
    assert len({r.pk for r in results}) == 1
    active_order.refresh_from_db()
    assert active_order.payout_committed_total == Decimal('100')
    assert _created_ledger().count() == 1


# --- H. mark_payout_paid ------------------------------------------------------

UTR = '123456789012'
ORDER_FINANCIAL_FIELDS = (
    'order_amount', 'income_rate', 'income_amount', 'payout_target',
    'payout_method_id', 'currency', 'reference', 'payout_committed_total',
)
PAYOUT_FROZEN_FIELDS = (
    'order_id', 'user_id', 'payout_method_id', 'upi_id_snapshot',
    'provider_code_snapshot', 'provider_name_snapshot', 'amount',
    'idempotency_key', 'created_by_id', 'created_at',
)


@pytest.fixture
def pending_payout(admin_user, active_order):
    return _pay(admin_user, active_order, Decimal('100'), key='payout-1')


def _mark(admin, payout, utr=UTR, key=None):
    return mark_payout_paid(
        admin=admin, payout_id=payout.pk, utr=utr, idempotency_key=key or next_key()
    )


def _ledger(action, order=None):
    entries = LedgerEntry.objects.filter(action=action)
    return entries.filter(order=order) if order else entries


def _paid_ledger(order=None):
    return _ledger(LedgerEntry.Action.PAYOUT_PAID, order)


def _completed_ledger(order=None):
    return _ledger(LedgerEntry.Action.ORDER_COMPLETED, order)


def _order_financials(order):
    values = Order.objects.values().get(pk=order.pk)
    return {field: values[field] for field in ORDER_FINANCIAL_FIELDS}


def _payout_frozen(payout):
    values = Payout.objects.values().get(pk=payout.pk)
    return {field: values[field] for field in PAYOUT_FROZEN_FIELDS}


def _assert_not_paid(payout, order, paid_total=Decimal('0'), status=Order.Status.PAYOUT_IN_PROGRESS):
    payout = Payout.objects.get(pk=payout.pk)
    assert payout.status != Payout.Status.PAID
    assert payout.utr == ''
    assert payout.paid_by is None and payout.paid_at is None
    order = Order.objects.get(pk=order.pk)
    assert order.payout_paid_total == paid_total
    assert order.status == status
    assert order.completed_at is None
    assert not _paid_ledger().exists()
    assert not _completed_ledger().exists()


# H1. permissions

@pytest.mark.parametrize('admin_fixture', ['admin_user', 'super_admin'])
def test_admin_marks_payout_paid(request, active_order, pending_payout, admin_fixture):
    admin = request.getfixturevalue(admin_fixture)
    before = timezone.now()

    payout = _mark(admin, pending_payout)

    payout.refresh_from_db()
    assert payout.pk == pending_payout.pk
    assert payout.status == Payout.Status.PAID
    assert payout.utr == UTR
    assert payout.paid_by == admin
    assert payout.paid_at is not None and payout.paid_at >= before


@pytest.mark.parametrize('extra', [
    {},                                                 # member
    {'is_active': False},
    {'role': User.Role.ADMIN, 'is_active': False},
    {'role': User.Role.SUPER_ADMIN, 'is_active': False},
])
def test_non_admin_or_inactive_user_cannot_mark_paid(make_user, active_order, pending_payout, extra):
    with pytest.raises(PayoutPermissionDenied):
        _mark(make_user(**extra), pending_payout)
    _assert_not_paid(pending_payout, active_order)


def test_order_owner_cannot_mark_paid(member, active_order, pending_payout):
    with pytest.raises(PayoutPermissionDenied):
        _mark(member, pending_payout)
    _assert_not_paid(pending_payout, active_order)


def test_anonymous_cannot_mark_paid(active_order, pending_payout):
    with pytest.raises(PayoutPermissionDenied):
        _mark(AnonymousUser(), pending_payout)
    _assert_not_paid(pending_payout, active_order)


@pytest.mark.parametrize('payout_id', [999999, 'abc', None])
def test_unknown_payout(admin_user, active_order, pending_payout, payout_id):
    with pytest.raises(PayoutNotFound):
        mark_payout_paid(admin=admin_user, payout_id=payout_id, utr=UTR, idempotency_key=next_key())
    _assert_not_paid(pending_payout, active_order)


@pytest.mark.parametrize('key', ['', '   ', None, 'x' * 65])
def test_mark_paid_invalid_idempotency_key(admin_user, active_order, pending_payout, key):
    with pytest.raises(InvalidIdempotencyKey):
        mark_payout_paid(admin=admin_user, payout_id=pending_payout.pk, utr=UTR, idempotency_key=key)
    _assert_not_paid(pending_payout, active_order)


# H2. payout and order state

def test_paid_replay_with_same_utr_returns_payout(admin_user, super_admin, active_order, pending_payout):
    first = _mark(admin_user, pending_payout)
    payout_before = Payout.objects.values().get(pk=first.pk)
    order_before = Order.objects.values().get(pk=active_order.pk)

    again = _mark(super_admin, pending_payout)

    assert again.pk == first.pk
    assert Payout.objects.values().get(pk=first.pk) == payout_before
    assert Order.objects.values().get(pk=active_order.pk) == order_before
    assert _paid_ledger().count() == 1


def test_paid_replay_after_order_completed(admin_user, active_order):
    payout = _pay(admin_user, active_order, active_order.payout_target)
    _mark(admin_user, payout)

    assert _mark(admin_user, payout).pk == payout.pk
    assert _paid_ledger().count() == 1
    assert _completed_ledger().count() == 1


def test_paid_payout_with_different_utr_conflicts(admin_user, active_order, pending_payout):
    _mark(admin_user, pending_payout)
    order_before = Order.objects.values().get(pk=active_order.pk)

    with pytest.raises(PayoutAlreadyPaid):
        _mark(admin_user, pending_payout, utr='999999999999')

    assert Payout.objects.get(pk=pending_payout.pk).utr == UTR
    assert Order.objects.values().get(pk=active_order.pk) == order_before
    assert _paid_ledger().count() == 1


@pytest.mark.parametrize('status', [Payout.Status.FAILED, Payout.Status.CANCELLED])
def test_non_pending_payout_rejected(admin_user, active_order, pending_payout, status):
    Payout.objects.filter(pk=pending_payout.pk).update(status=status)

    with pytest.raises(PayoutNotPending):
        _mark(admin_user, pending_payout)

    assert Payout.objects.get(pk=pending_payout.pk).status == status
    _assert_not_paid(pending_payout, active_order)


def test_payout_on_active_order_rejected(admin_user, active_order, pending_payout):
    Order.objects.filter(pk=active_order.pk).update(status=Order.Status.ACTIVE)

    with pytest.raises(OrderNotInPayout):
        _mark(admin_user, pending_payout)
    _assert_not_paid(pending_payout, active_order, status=Order.Status.ACTIVE)


def test_payout_on_completed_order_rejected(admin_user, active_order):
    # Inconsistent state guard: a COMPLETED order must never pay again.
    stray = _pay(admin_user, active_order, Decimal('100'))
    rest = _pay(admin_user, active_order, Decimal('165'))
    _mark(admin_user, rest, utr='000000000001')
    Order.objects.filter(pk=active_order.pk).update(
        status=Order.Status.COMPLETED, payout_paid_total=active_order.payout_target,
    )

    with pytest.raises(OrderNotInPayout):
        _mark(admin_user, stray)

    assert Payout.objects.get(pk=stray.pk).status == Payout.Status.PENDING
    assert _paid_ledger().count() == 1


# H3. UTR

def test_utr_leading_zeros_preserved(admin_user, pending_payout):
    _mark(admin_user, pending_payout, utr='000012345678')
    assert Payout.objects.get(pk=pending_payout.pk).utr == '000012345678'


@pytest.mark.parametrize('utr', [
    '',
    '12345678901',                  # 11 digits
    '1234567890123',                # 13 digits
    '123456 789012',
    ' 123456789012',
    '123456789012 ',
    '123456-789012',
    '123456789012\n',
    '12345678901\n',
    '١٢٣٤٥٦٧٨٩٠١٢',                 # Arabic-Indic digits
    '１２３４５６７８９０１２',     # full-width digits
    'UTR123456789',
    123456789012,
    None,
    b'123456789012',
])
def test_invalid_utr_rejected(admin_user, active_order, pending_payout, utr):
    with pytest.raises(InvalidPayoutUtr):
        _mark(admin_user, pending_payout, utr=utr)
    _assert_not_paid(pending_payout, active_order)


def test_utr_of_another_paid_payout_rejected(admin_user, active_order, pending_payout):
    other = _pay(admin_user, active_order, Decimal('50'))
    _mark(admin_user, other)

    with pytest.raises(PayoutUtrAlreadyUsed):
        _mark(admin_user, pending_payout)

    assert Payout.objects.get(pk=pending_payout.pk).status == Payout.Status.PENDING
    assert Order.objects.get(pk=active_order.pk).payout_paid_total == Decimal('50')
    assert _paid_ledger().count() == 1


def test_utr_of_confirmed_payin_rejected(admin_user, active_order, pending_payout):
    # active_order's pay-in was confirmed with UTR 555555555555.
    payin = PayIn.objects.get(utr='555555555555')
    assert payin.status == PayIn.Status.CONFIRMED
    payin_before = PayIn.objects.values().get(pk=payin.pk)

    with pytest.raises(PayoutUtrAlreadyUsed):
        _mark(admin_user, pending_payout, utr='555555555555')

    assert PayIn.objects.values().get(pk=payin.pk) == payin_before
    _assert_not_paid(pending_payout, active_order)


def test_utr_of_under_review_payin_rejected(admin_user, member, make_order, active_order, pending_payout):
    other = make_order(member)
    intent = get_pay_in_intent(user=member, order_id=other.pk)
    payin = submit_pay_in_utr(
        user=member, order_id=other.pk, utr='777777777777',
        idempotency_key=next_key(), intent_token=intent.intent_token,
    )
    assert payin.status == PayIn.Status.UNDER_REVIEW
    payin_before = PayIn.objects.values().get(pk=payin.pk)

    with pytest.raises(PayoutUtrAlreadyUsed):
        _mark(admin_user, pending_payout, utr='777777777777')

    assert PayIn.objects.values().get(pk=payin.pk) == payin_before
    _assert_not_paid(pending_payout, active_order)


# H4. totals and completion

def test_paid_total_increases_committed_total_does_not(admin_user, active_order, pending_payout):
    _mark(admin_user, pending_payout)

    active_order.refresh_from_db()
    assert active_order.payout_paid_total == Decimal('100')
    assert active_order.payout_committed_total == Decimal('100')


def test_partial_payment_leaves_order_in_progress(admin_user, active_order, pending_payout):
    _mark(admin_user, pending_payout)

    active_order.refresh_from_db()
    assert active_order.status == Order.Status.PAYOUT_IN_PROGRESS
    assert active_order.completed_at is None
    assert not _completed_ledger().exists()


def test_multiple_payouts_paid_until_completed(admin_user, super_admin, active_order):
    first = _pay(admin_user, active_order, Decimal('100'))
    second = _pay(admin_user, active_order, Decimal('60'))
    third = _pay(admin_user, active_order, Decimal('105'))

    _mark(admin_user, first, utr='000000000001')
    _mark(super_admin, second, utr='000000000002')
    active_order.refresh_from_db()
    assert active_order.payout_paid_total == Decimal('160')
    assert active_order.status == Order.Status.PAYOUT_IN_PROGRESS
    assert active_order.completed_at is None

    before = timezone.now()
    _mark(admin_user, third, utr='000000000003')
    active_order.refresh_from_db()
    assert active_order.payout_paid_total == active_order.payout_target == Decimal('265')
    assert active_order.payout_committed_total == Decimal('265')
    assert active_order.status == Order.Status.COMPLETED
    assert active_order.completed_at is not None and active_order.completed_at >= before
    assert _paid_ledger(active_order).count() == 3
    assert _completed_ledger(active_order).count() == 1


def test_fully_committed_but_partly_paid_order_not_completed(admin_user, active_order):
    first = _pay(admin_user, active_order, Decimal('200'))
    _pay(admin_user, active_order, Decimal('65'))

    _mark(admin_user, first)

    active_order.refresh_from_db()
    assert active_order.payout_committed_total == active_order.payout_target
    assert active_order.payout_paid_total == Decimal('200')
    assert active_order.status == Order.Status.PAYOUT_IN_PROGRESS


def test_paid_total_cannot_exceed_committed_total(admin_user, active_order, pending_payout):
    # Inconsistent state guard: 50 already counted as paid + 100 > 100 committed.
    Order.objects.filter(pk=active_order.pk).update(payout_paid_total=Decimal('50'))

    with pytest.raises(PayoutTotalConflict):
        _mark(admin_user, pending_payout)
    _assert_not_paid(pending_payout, active_order, paid_total=Decimal('50'))


def test_paid_total_cannot_exceed_target(admin_user, active_order):
    payout = _pay(admin_user, active_order, active_order.payout_target)
    Order.objects.filter(pk=active_order.pk).update(payout_paid_total=Decimal('1'))

    with pytest.raises(PayoutTotalConflict):
        _mark(admin_user, payout)
    _assert_not_paid(payout, active_order, paid_total=Decimal('1'))


# H5. ledger

def test_payout_paid_ledger_entry(admin_user, active_order, pending_payout):
    _mark(admin_user, pending_payout)

    entry = _paid_ledger().get()
    assert entry.actor == admin_user
    assert entry.order == active_order
    assert entry.payout == pending_payout
    assert entry.payin is None
    assert entry.amount == Decimal('100')
    # Outstanding payout owed by the platform: 265 - 0, then 265 - 100.
    assert entry.balance_before == Decimal('265')
    assert entry.balance_after == Decimal('165')
    assert entry.reference_id == active_order.reference
    assert not _completed_ledger().exists()
    # Nothing else is written: the setup's PAYIN_CONFIRMED and PAYOUT_CREATED stay single.
    assert _created_ledger().count() == 1
    assert LedgerEntry.objects.filter(action=LedgerEntry.Action.PAYIN_CONFIRMED).count() == 1


def test_final_payment_ledger_entries(admin_user, active_order, pending_payout):
    rest = _pay(admin_user, active_order, Decimal('165'))
    _mark(admin_user, pending_payout, utr='000000000001')
    entries_before = LedgerEntry.objects.count()

    _mark(admin_user, rest, utr='000000000002')

    assert LedgerEntry.objects.count() == entries_before + 2
    paid = _paid_ledger().get(payout=rest)
    assert paid.amount == Decimal('165')
    assert paid.balance_before == Decimal('165')
    assert paid.balance_after == Decimal('0')

    completed = _completed_ledger().get()
    assert completed.actor == admin_user
    assert completed.order == active_order
    assert completed.payout is None and completed.payin is None
    assert completed.amount == active_order.payout_target
    assert completed.balance_before is None and completed.balance_after is None
    assert completed.reference_id == active_order.reference


def test_replay_writes_no_ledger_entries(admin_user, active_order, pending_payout):
    _mark(admin_user, pending_payout)
    entries_before = LedgerEntry.objects.count()

    _mark(admin_user, pending_payout)
    _mark(admin_user, pending_payout, key='another-key')

    assert LedgerEntry.objects.count() == entries_before


# H6. snapshots

def test_mark_paid_keeps_payout_and_order_snapshots(admin_user, active_order, pending_payout):
    payout_before = _payout_frozen(pending_payout)
    order_before = _order_financials(active_order)

    _mark(admin_user, pending_payout)

    assert _payout_frozen(pending_payout) == payout_before
    assert _order_financials(active_order) == order_before
    payout = Payout.objects.get(pk=pending_payout.pk)
    assert payout.amount == Decimal('100')
    assert payout.idempotency_key == 'payout-1'
    assert payout.upi_id_snapshot == 'member@ybl'


# H7. atomicity

def test_failure_after_payout_update_rolls_back_everything(monkeypatch, admin_user, active_order, pending_payout):
    def boom(**kwargs):
        raise RuntimeError('ledger unavailable')

    monkeypatch.setattr(services, 'record_ledger_entry', boom)

    with pytest.raises(RuntimeError):
        _mark(admin_user, pending_payout)

    assert Payout.objects.get(pk=pending_payout.pk).status == Payout.Status.PENDING
    _assert_not_paid(pending_payout, active_order)


def test_failure_on_completion_rolls_back_payment(monkeypatch, admin_user, active_order):
    payout = _pay(admin_user, active_order, active_order.payout_target)
    real = services.record_ledger_entry

    def fail_on_completion(**kwargs):
        if kwargs['action'] == LedgerEntry.Action.ORDER_COMPLETED:
            raise RuntimeError('ledger unavailable')
        return real(**kwargs)

    monkeypatch.setattr(services, 'record_ledger_entry', fail_on_completion)

    with pytest.raises(RuntimeError):
        _mark(admin_user, payout)

    assert Payout.objects.get(pk=payout.pk).status == Payout.Status.PENDING
    _assert_not_paid(payout, active_order)


# H8. concurrency (PostgreSQL only)

@postgres_only
@pytest.mark.django_db(transaction=True)
def test_concurrent_mark_paid_pays_once(admin_user, super_admin, active_order):
    payout = _pay(admin_user, active_order, Decimal('100'))

    results = _run_concurrently(
        lambda: _mark(admin_user, payout),
        lambda: _mark(super_admin, payout),
    )

    # One pays; the other waits on the order lock, then sees PAID with the
    # same UTR and replays.
    assert all(isinstance(r, Payout) for r in results), results
    assert {r.pk for r in results} == {payout.pk}
    active_order.refresh_from_db()
    assert active_order.payout_paid_total == Decimal('100')
    assert _paid_ledger().count() == 1


@postgres_only
@pytest.mark.django_db(transaction=True)
def test_concurrent_mark_paid_with_different_utrs_pays_once(admin_user, super_admin, active_order):
    payout = _pay(admin_user, active_order, Decimal('100'))

    results = _run_concurrently(
        lambda: _mark(admin_user, payout, utr='000000000001'),
        lambda: _mark(super_admin, payout, utr='000000000002'),
    )

    paid = [r for r in results if isinstance(r, Payout)]
    errors = [r for r in results if not isinstance(r, Payout)]
    assert len(paid) == 1
    assert len(errors) == 1 and isinstance(errors[0], PayoutAlreadyPaid)
    active_order.refresh_from_db()
    assert active_order.payout_paid_total == Decimal('100')
    assert _paid_ledger().count() == 1


@postgres_only
@pytest.mark.django_db(transaction=True)
def test_concurrent_payouts_cannot_share_utr(admin_user, super_admin, active_order):
    first = _pay(admin_user, active_order, Decimal('100'))
    second = _pay(admin_user, active_order, Decimal('60'))

    results = _run_concurrently(
        lambda: _mark(admin_user, first),
        lambda: _mark(super_admin, second),
    )

    paid = [r for r in results if isinstance(r, Payout)]
    errors = [r for r in results if not isinstance(r, Payout)]
    assert len(paid) == 1
    assert len(errors) == 1 and isinstance(errors[0], PayoutUtrAlreadyUsed)
    active_order.refresh_from_db()
    assert active_order.payout_paid_total == paid[0].amount
    assert _paid_ledger().count() == 1


# --- I. get_payout_method_for_admin -------------------------------------------

def _view(admin, payout, **kwargs):
    return get_payout_method_for_admin(admin=admin, payout_id=payout.pk, **kwargs)


def _view_events():
    return AuditEvent.objects.filter(action=AuditEvent.Action.PAYOUT_METHOD_VIEWED)


def _assert_original_destination(view, payout):
    assert view.payout_id == payout.pk
    assert view.upi_id == 'member@ybl'
    assert view.provider_code == 'PHONEPE'
    assert view.provider_name == 'PhonePe'
    assert view.qr_image == 'payout_qr/test.png'


# I1. permissions and lookup

@pytest.mark.parametrize('viewer', ['admin_user', 'super_admin'])
def test_admin_views_payout_method(request, member, active_order, pending_payout, viewer):
    admin = request.getfixturevalue(viewer)

    view = _view(admin, pending_payout)

    assert isinstance(view, AdminPayoutMethodView)
    _assert_original_destination(view, pending_payout)
    assert view.order_id == active_order.pk
    assert view.order_reference == active_order.reference
    assert view.user_id == member.pk
    assert view.user_name == member.name
    assert view.amount == Decimal('100')
    assert view.status == Payout.Status.PENDING
    assert view.qr_sha256 == pending_payout.payout_method.qr_sha256


def test_view_exposes_only_payout_fields(admin_user, pending_payout):
    view = _view(admin_user, pending_payout)
    assert set(vars(view)) == {
        'payout_id', 'order_id', 'order_reference', 'user_id', 'user_name', 'amount',
        'status', 'provider_code', 'provider_name', 'upi_id', 'qr_image', 'qr_sha256',
    }


@pytest.mark.parametrize('extra', [
    {},                                                 # another member
    {'is_active': False},
    {'role': User.Role.ADMIN, 'is_active': False},
    {'role': User.Role.SUPER_ADMIN, 'is_active': False},
])
def test_non_admin_or_inactive_user_cannot_view(make_user, pending_payout, extra):
    with pytest.raises(PayoutPermissionDenied):
        _view(make_user(**extra), pending_payout)
    assert not _view_events().exists()


def test_payout_owner_cannot_view_through_admin_service(member, pending_payout):
    with pytest.raises(PayoutPermissionDenied):
        _view(member, pending_payout)
    assert not _view_events().exists()


def test_anonymous_cannot_view(pending_payout):
    with pytest.raises(PayoutPermissionDenied):
        _view(AnonymousUser(), pending_payout)
    assert not _view_events().exists()


@pytest.mark.parametrize('payout_id', [999999, 'abc', None])
def test_view_unknown_payout(admin_user, pending_payout, payout_id):
    with pytest.raises(PayoutNotFound):
        get_payout_method_for_admin(admin=admin_user, payout_id=payout_id)
    assert not _view_events().exists()


# I2. any payout status

def test_view_paid_payout(admin_user, pending_payout):
    _mark(admin_user, pending_payout)

    view = _view(admin_user, pending_payout)

    assert view.status == Payout.Status.PAID
    _assert_original_destination(view, pending_payout)


@pytest.mark.parametrize('status', [Payout.Status.FAILED, Payout.Status.CANCELLED])
def test_view_failed_or_cancelled_payout(admin_user, pending_payout, status):
    Payout.objects.filter(pk=pending_payout.pk).update(status=status)

    view = _view(admin_user, pending_payout)

    assert view.status == status
    _assert_original_destination(view, pending_payout)


# I3. fixed destination

def test_new_method_and_default_change_do_not_change_view(admin_user, member, make_method, pending_payout):
    newer = make_method(member, 'newer@ybl', qr_image='payout_qr/newer.png')
    set_default_payout_method(user=member, method_id=newer.pk)

    view = _view(admin_user, pending_payout)

    _assert_original_destination(view, pending_payout)


def test_deactivated_original_method_does_not_change_view(admin_user, member, make_method, pending_payout):
    original = pending_payout.payout_method
    newer = make_method(member, 'newer@ybl', qr_image='payout_qr/newer.png')
    set_default_payout_method(user=member, method_id=newer.pk)
    deactivate_payout_method(user=member, method_id=original.pk)

    view = _view(admin_user, pending_payout)

    _assert_original_destination(view, pending_payout)


def test_method_with_other_provider_does_not_change_view(admin_user, member, pending_payout):
    gpay = PaymentProvider.objects.create(
        code='GPAY', display_name='Google Pay', enabled_for_payout_methods=True,
    )
    member.payout_methods.create(provider=gpay, upi_id='member@okaxis', qr_image='payout_qr/gpay.png')

    view = _view(admin_user, pending_payout)

    _assert_original_destination(view, pending_payout)


def test_provider_info_comes_from_payout_snapshot(admin_user, provider, pending_payout):
    # Catalog edits after the payout was created must not rewrite history.
    PaymentProvider.objects.filter(pk=provider.pk).update(display_name='PhonePe Renamed')

    view = _view(admin_user, pending_payout)

    assert view.provider_name == 'PhonePe'
    assert view.provider_code == 'PHONEPE'


# I4. read-only apart from the audit event

def test_view_changes_no_financial_state(admin_user, active_order, pending_payout):
    paid = _pay(admin_user, active_order, Decimal('50'))
    _mark(admin_user, paid)
    payouts_before = list(Payout.objects.order_by('pk').values())
    order_before = Order.objects.values().get(pk=active_order.pk)
    methods_before = list(active_order.user.payout_methods.order_by('pk').values())
    ledger_before = list(LedgerEntry.objects.order_by('pk').values())

    _view(admin_user, pending_payout)
    _view(admin_user, paid)

    assert list(Payout.objects.order_by('pk').values()) == payouts_before
    assert Order.objects.values().get(pk=active_order.pk) == order_before
    assert list(active_order.user.payout_methods.order_by('pk').values()) == methods_before
    assert list(LedgerEntry.objects.order_by('pk').values()) == ledger_before


def test_view_records_one_audit_event(admin_user, active_order, pending_payout):
    other_events = AuditEvent.objects.exclude(action=AuditEvent.Action.PAYOUT_METHOD_VIEWED).count()

    _view(admin_user, pending_payout, ip_address='10.0.0.1')

    event = _view_events().get()
    assert event.actor == admin_user
    assert event.target_type == 'paymethods.UserPayoutMethod'
    assert event.target_id == str(pending_payout.payout_method_id)
    assert event.ip_address == '10.0.0.1'
    assert event.metadata == {'payout_id': pending_payout.pk, 'order_id': active_order.pk}
    # Identifiers only: the UPI ID and QR never reach the audit log.
    assert 'member@ybl' not in str(event.metadata)
    assert 'payout_qr' not in str(event.metadata)
    assert AuditEvent.objects.exclude(action=AuditEvent.Action.PAYOUT_METHOD_VIEWED).count() == other_events


def test_each_view_is_audited(admin_user, super_admin, pending_payout):
    _view(admin_user, pending_payout)
    _view(super_admin, pending_payout)

    assert list(_view_events().values_list('actor_id', flat=True)) == [admin_user.pk, super_admin.pk]
