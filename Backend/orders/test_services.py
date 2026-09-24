import threading
from datetime import timedelta
from decimal import Decimal

import pytest
from django.contrib.auth.models import AnonymousUser
from django.db import connection
from django.utils import timezone

from audit.models import LedgerEntry
from orders import services
from orders.exceptions import (
    ClaimPermissionDenied,
    IdempotencyKeyConflict,
    InvalidIdempotencyKey,
    NoActivePayoutMethod,
    OfferNotFound,
    OfferSoldOut,
    OfferUnavailable,
    PayoutMethodUnavailable,
)
from orders.models import Currency, Order, OrderOffer
from orders.services import claim_order
from paymethods.services import deactivate_payout_method, set_default_payout_method

from conftest import next_key


@pytest.fixture
def member(make_user, make_method):
    user = make_user()
    make_method(user, 'member@ybl', is_default=True)
    return user


def _offer(**values):
    defaults = dict(
        currency=Currency.INR,
        order_amount=Decimal('255'),
        income_rate=Decimal('4.00'),
        is_active=True,
    )
    defaults.update(values)
    return OrderOffer.objects.create(**defaults)


def _claim(user, offer, key=None, **kwargs):
    return claim_order(
        user=user, offer_id=offer.pk, idempotency_key=key or next_key(), **kwargs
    )


def _claimed_ledger(order=None):
    entries = LedgerEntry.objects.filter(action=LedgerEntry.Action.ORDER_CLAIMED)
    return entries.filter(order=order) if order else entries


# --- A. successful claim ------------------------------------------------------

def test_successful_claim(member):
    offer = _offer()
    default = member.payout_methods.get(is_default=True)

    order = _claim(member, offer)

    order.refresh_from_db()
    assert Order.objects.count() == 1
    assert order.user == member
    assert order.offer == offer
    assert order.status == Order.Status.PAYMENT_REQUIRED
    assert order.currency == 'INR'
    assert order.order_amount == Decimal('255')
    assert order.income_rate == Decimal('4.00')
    assert order.income_amount == Decimal('10')
    assert order.payout_target == Decimal('265')
    assert order.payout_committed_total == 0
    assert order.payout_paid_total == 0
    assert order.payout_method == default
    assert order.reference.startswith('HW-')
    assert not order.pay_ins.exists()

    entry = _claimed_ledger(order).get()
    assert entry.actor == member
    assert entry.amount == Decimal('255')
    assert entry.reference_id == order.reference
    assert entry.balance_before is None and entry.balance_after is None


# --- B. explicit payout method ----------------------------------------------

def test_explicit_payout_method_is_attached_and_fixed(member, make_method):
    offer = _offer()
    chosen = make_method(member, 'chosen@ybl')

    order = _claim(member, offer, payout_method_id=chosen.pk)
    assert order.payout_method == chosen

    set_default_payout_method(user=member, method_id=chosen.pk)
    other = make_method(member, 'later@ybl')
    set_default_payout_method(user=member, method_id=other.pk)
    deactivate_payout_method(user=member, method_id=chosen.pk)

    order.refresh_from_db()
    assert order.payout_method == chosen


def test_default_change_does_not_touch_existing_order(member, make_method):
    original_default = member.payout_methods.get(is_default=True)
    order = _claim(member, _offer())

    new_method = make_method(member, 'new@ybl')
    set_default_payout_method(user=member, method_id=new_method.pk)

    order.refresh_from_db()
    assert order.payout_method == original_default


# --- C. no payout method ----------------------------------------------------

def test_claim_rejected_without_any_payout_method(make_user):
    user = make_user()
    with pytest.raises(NoActivePayoutMethod):
        _claim(user, _offer())
    assert not Order.objects.exists()


def test_claim_rejected_after_last_method_deactivated(member):
    method = member.payout_methods.get()
    deactivate_payout_method(user=member, method_id=method.pk)
    with pytest.raises(NoActivePayoutMethod):
        _claim(member, _offer())
    assert not Order.objects.exists()


# --- D. invalid payout method -----------------------------------------------

def test_inactive_payout_method_rejected(member, make_method):
    inactive = make_method(member, 'old@ybl')
    deactivate_payout_method(user=member, method_id=inactive.pk)
    with pytest.raises(PayoutMethodUnavailable):
        _claim(member, _offer(), payout_method_id=inactive.pk)
    assert not Order.objects.exists()


@pytest.mark.parametrize('method_id', [999999, 'abc'])
def test_unknown_payout_method_rejected(member, method_id):
    with pytest.raises(PayoutMethodUnavailable):
        _claim(member, _offer(), payout_method_id=method_id)


# --- E. offer validation ----------------------------------------------------

@pytest.mark.parametrize('offer_id', [999999, 'abc', None, 0])
def test_unknown_offer_rejected(member, offer_id):
    with pytest.raises(OfferNotFound):
        claim_order(user=member, offer_id=offer_id, idempotency_key=next_key())
    assert not Order.objects.exists()


@pytest.mark.parametrize(
    'values',
    [
        dict(is_active=False),
        dict(valid_to=timezone.now() - timedelta(minutes=1)),                 # expired
        dict(valid_from=timezone.now() + timedelta(hours=1)),                 # not yet valid
        dict(
            valid_from=timezone.now() - timedelta(days=2),
            valid_to=timezone.now() - timedelta(days=1),
        ),
    ],
)
def test_unavailable_offer_rejected(member, values):
    with pytest.raises(OfferUnavailable):
        _claim(member, _offer(**values))
    assert not Order.objects.exists()
    assert not _claimed_ledger().exists()


def test_offer_inside_validity_window_is_claimable(member):
    offer = _offer(
        valid_from=timezone.now() - timedelta(hours=1),
        valid_to=timezone.now() + timedelta(hours=1),
    )
    _claim(member, offer)


def test_zero_quantity_rejected(member):
    offer = _offer(quantity_available=0)
    with pytest.raises(OfferSoldOut):
        _claim(member, offer)
    offer.refresh_from_db()
    assert offer.quantity_available == 0
    assert not Order.objects.exists()


def test_quantity_decremented_exactly_once(member):
    offer = _offer(quantity_available=5)
    _claim(member, offer)
    offer.refresh_from_db()
    assert offer.quantity_available == 4


# --- F. quantity ------------------------------------------------------------

def test_last_unit_then_sold_out(make_user, make_method):
    first, second = make_user(), make_user()
    make_method(first, 'first@ybl', is_default=True)
    make_method(second, 'second@ybl', is_default=True)
    offer = _offer(quantity_available=1)

    _claim(first, offer)
    offer.refresh_from_db()
    assert offer.quantity_available == 0

    with pytest.raises(OfferSoldOut):
        _claim(second, offer)
    offer.refresh_from_db()
    assert offer.quantity_available == 0
    assert Order.objects.count() == 1


def test_unlimited_quantity_stays_unlimited(member):
    offer = _offer(quantity_available=None)
    for _ in range(3):
        _claim(member, offer)
    offer.refresh_from_db()
    assert offer.quantity_available is None
    assert Order.objects.count() == 3


# --- G. snapshot behaviour --------------------------------------------------

def test_offer_changes_do_not_touch_claimed_order(member):
    offer = _offer()
    order = _claim(member, offer)

    offer.income_rate = Decimal('5.00')
    offer.order_amount = Decimal('300')
    offer.save()

    order.refresh_from_db()
    assert (order.order_amount, order.income_rate) == (Decimal('255'), Decimal('4.00'))
    assert (order.income_amount, order.payout_target) == (Decimal('10'), Decimal('265'))

    newer = _claim(member, offer)
    assert newer.income_rate == Decimal('5.00')
    assert newer.income_amount == Decimal('15')      # 300 x 5% = 15
    assert newer.payout_target == Decimal('315')


# --- H. calculation ---------------------------------------------------------

@pytest.mark.parametrize(
    'order_amount, income_rate, income, target',
    [
        ('255', '4.00', '10', '265'),   # 10.20 -> 10
        ('250', '5.00', '13', '263'),   # 12.50 -> 13 (half up)
        ('255', '5.00', '13', '268'),   # 12.75 -> 13
        ('1133', '4.00', '45', '1178'), # 45.32 -> 45
        ('500', '0.00', '0', '500'),
        ('255', '100.00', '255', '510'),
    ],
)
def test_claim_calculation(member, order_amount, income_rate, income, target):
    order = _claim(member, _offer(order_amount=Decimal(order_amount), income_rate=Decimal(income_rate)))
    order.refresh_from_db()
    assert order.income_amount == Decimal(income)
    assert order.payout_target == Decimal(target)
    for value in (order.order_amount, order.income_amount, order.payout_target):
        assert isinstance(value, Decimal)
        assert value == value.to_integral_value()


def test_calculation_goes_through_shared_helper(member, monkeypatch):
    calls = []

    def spy(order_amount, income_rate):
        calls.append((order_amount, income_rate))
        return Decimal('10')

    monkeypatch.setattr(services, 'calculate_income_amount', spy)
    _claim(member, _offer())
    assert calls == [(Decimal('255.00'), Decimal('4.00'))]


# --- I. idempotency ---------------------------------------------------------

def test_same_key_returns_same_order(member):
    offer = _offer(quantity_available=5)
    first = _claim(member, offer, key='claim-1')
    again = _claim(member, offer, key='claim-1')

    assert again.pk == first.pk
    assert Order.objects.count() == 1
    assert _claimed_ledger().count() == 1
    offer.refresh_from_db()
    assert offer.quantity_available == 4


def test_retry_succeeds_even_after_offer_becomes_unavailable(member):
    offer = _offer(quantity_available=1)
    first = _claim(member, offer, key='claim-1')
    offer.refresh_from_db()
    offer.is_active = False
    offer.save()

    assert _claim(member, offer, key='claim-1').pk == first.pk


def test_retry_with_explicit_matching_method_returns_order(member):
    method = member.payout_methods.get()
    offer = _offer()
    first = _claim(member, offer, key='claim-1', payout_method_id=method.pk)
    assert _claim(member, offer, key='claim-1', payout_method_id=method.pk).pk == first.pk
    assert _claim(member, offer, key='claim-1').pk == first.pk


def test_same_key_for_different_offer_rejected(member):
    _claim(member, _offer(), key='claim-1')
    with pytest.raises(IdempotencyKeyConflict):
        _claim(member, _offer(order_amount=Decimal('500')), key='claim-1')
    assert Order.objects.count() == 1


def test_same_key_for_different_payout_method_rejected(member, make_method):
    offer = _offer()
    _claim(member, offer, key='claim-1')
    other = make_method(member, 'other@ybl')
    with pytest.raises(IdempotencyKeyConflict):
        _claim(member, offer, key='claim-1', payout_method_id=other.pk)
    assert Order.objects.count() == 1


def test_same_key_is_independent_per_member(member, make_user, make_method):
    other = make_user()
    make_method(other, 'other@ybl', is_default=True)
    offer = _offer()
    a = _claim(member, offer, key='shared-key')
    b = _claim(other, offer, key='shared-key')
    assert a.pk != b.pk


@pytest.mark.parametrize('key', ['', '   ', None, 123, 'k' * 65])
def test_invalid_idempotency_key_rejected(member, key):
    with pytest.raises(InvalidIdempotencyKey):
        claim_order(user=member, offer_id=_offer().pk, idempotency_key=key)
    assert not Order.objects.exists()


def test_duplicate_key_race_falls_back_to_existing_order(member, monkeypatch):
    offer = _offer(quantity_available=5)
    first = _claim(member, offer, key='claim-1')

    # Simulate a concurrent request whose check ran before the first
    # commit: the in-transaction lookup misses, the insert hits the unique
    # constraint, and the fallback lookup finds the winner.
    real_lookup = services._existing_claim
    calls = {'n': 0}

    def stale_then_real(user, key):
        calls['n'] += 1
        return None if calls['n'] == 1 else real_lookup(user, key)

    monkeypatch.setattr(services, '_existing_claim', stale_then_real)
    again = _claim(member, offer, key='claim-1')

    assert again.pk == first.pk
    assert Order.objects.count() == 1
    assert _claimed_ledger().count() == 1
    offer.refresh_from_db()
    assert offer.quantity_available == 4  # the losing attempt's decrement rolled back


# --- J. atomic failure ------------------------------------------------------

def test_failure_after_decrement_rolls_everything_back(member, monkeypatch):
    offer = _offer(quantity_available=3)

    def boom(**kwargs):
        raise RuntimeError('ledger write failed')

    monkeypatch.setattr(services, 'record_ledger_entry', boom)
    with pytest.raises(RuntimeError):
        _claim(member, offer)

    offer.refresh_from_db()
    assert offer.quantity_available == 3
    assert not Order.objects.exists()
    assert not LedgerEntry.objects.exists()


# --- K. ownership / security ------------------------------------------------

def test_cannot_use_another_members_payout_method(member, make_user, make_method):
    other = make_user()
    foreign = make_method(other, 'other@ybl', is_default=True)
    with pytest.raises(PayoutMethodUnavailable):
        _claim(member, _offer(), payout_method_id=foreign.pk)
    assert not Order.objects.exists()


@pytest.mark.parametrize(
    'make_actor',
    [
        lambda make_user: AnonymousUser(),
        lambda make_user: make_user(role='ADMIN'),
        lambda make_user: make_user(role='SUPER_ADMIN'),
        lambda make_user: make_user(is_verified=False),
        lambda make_user: make_user(is_active=False),
    ],
)
def test_only_verified_active_members_can_claim(make_user, make_method, make_actor):
    actor = make_actor(make_user)
    if getattr(actor, 'pk', None):
        make_method(actor, 'actor@ybl', is_default=True)
    offer = _offer(quantity_available=1)
    with pytest.raises(ClaimPermissionDenied):
        claim_order(user=actor, offer_id=offer.pk, idempotency_key=next_key())
    offer.refresh_from_db()
    assert offer.quantity_available == 1
    assert not Order.objects.exists()


# --- L. concurrency (PostgreSQL only) ---------------------------------------

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
def test_concurrent_claims_for_last_unit(make_user, make_method):
    first, second = make_user(), make_user()
    make_method(first, 'first@ybl', is_default=True)
    make_method(second, 'second@ybl', is_default=True)
    offer = _offer(quantity_available=1)

    results = _run_concurrently(
        lambda: claim_order(user=first, offer_id=offer.pk, idempotency_key='k1'),
        lambda: claim_order(user=second, offer_id=offer.pk, idempotency_key='k2'),
    )

    orders = [r for r in results if isinstance(r, Order)]
    errors = [r for r in results if not isinstance(r, Order)]
    assert len(orders) == 1
    assert len(errors) == 1 and isinstance(errors[0], OfferSoldOut)
    offer.refresh_from_db()
    assert offer.quantity_available == 0
    assert Order.objects.count() == 1
    assert _claimed_ledger().count() == 1


@postgres_only
@pytest.mark.django_db(transaction=True)
def test_concurrent_retries_with_same_key_create_one_order(make_user, make_method):
    user = make_user()
    make_method(user, 'user@ybl', is_default=True)
    offer = _offer(quantity_available=5)

    results = _run_concurrently(
        *[lambda: claim_order(user=user, offer_id=offer.pk, idempotency_key='same') for _ in range(3)]
    )

    assert all(isinstance(r, Order) for r in results), results
    assert len({r.pk for r in results}) == 1
    offer.refresh_from_db()
    assert offer.quantity_available == 4
    assert _claimed_ledger().count() == 1
