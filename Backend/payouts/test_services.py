import threading
from decimal import Decimal

import pytest
from django.contrib.auth.models import AnonymousUser
from django.db import connection

from audit.models import LedgerEntry
from conftest import next_key
from core.models import User
from orders.models import Order
from payins.services import confirm_pay_in, get_pay_in_intent, submit_pay_in_utr
from paymethods.services import deactivate_payout_method, set_default_payout_method
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
from payouts.services import create_payout

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
