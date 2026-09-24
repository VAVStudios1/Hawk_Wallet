from decimal import Decimal

import pytest
from django.core.exceptions import ValidationError
from django.db import IntegrityError, transaction
from django.utils import timezone

from audit.mixins import AppendOnlyError, ImmutableFieldError
from conftest import next_key
from payouts.models import Payout


@pytest.fixture
def order_with_method(user, make_method, make_order):
    method = make_method(user, 'rahul@ybl', is_default=True)
    return make_order(user, payout_method=method), method


@pytest.fixture
def make_payout(admin_user):
    def _make(order, method, amount, **extra):
        values = dict(
            order=order,
            user=order.user,
            payout_method=method,
            amount=amount,
            created_by=admin_user,
            idempotency_key=next_key(),
        )
        values.update(extra)
        return Payout.objects.create(**values)
    return _make


@pytest.mark.parametrize(
    'amounts',
    [
        ['50', '80', '40', '95'],
        ['20', '30', '50', '10', '70', '75', '10'],
    ],
)
def test_order_can_have_many_payouts_up_to_target(order_with_method, make_payout, amounts):
    order, method = order_with_method
    for amount in amounts:
        make_payout(order, method, Decimal(amount))
    assert order.payouts.count() == len(amounts)
    assert sum(p.amount for p in order.payouts.all()) == order.payout_target == Decimal('265')


def test_destination_snapshot_taken_from_method(order_with_method, make_payout):
    order, method = order_with_method
    payout = make_payout(order, method, Decimal('50'), upi_id_snapshot='attacker@ybl')
    assert payout.upi_id_snapshot == 'rahul@ybl'
    assert payout.provider_code_snapshot == 'PHONEPE'
    assert payout.provider_name_snapshot == 'PhonePe'


def test_snapshot_survives_method_deactivation(order_with_method, make_payout):
    order, method = order_with_method
    payout = make_payout(order, method, Decimal('50'))
    method.is_default = False
    method.is_active = False
    method.save()
    payout.refresh_from_db()
    assert payout.upi_id_snapshot == 'rahul@ybl'


@pytest.mark.parametrize('amount', [Decimal('0'), Decimal('-10'), Decimal('50.50')])
def test_payout_amount_must_be_positive_whole_rupees(order_with_method, make_payout, amount):
    order, method = order_with_method
    with pytest.raises(IntegrityError), transaction.atomic():
        make_payout(order, method, amount)


def test_paid_payout_requires_paid_at(order_with_method, make_payout):
    order, method = order_with_method
    with pytest.raises(IntegrityError), transaction.atomic():
        make_payout(order, method, Decimal('50'), status=Payout.Status.PAID, utr='123456789012')


def test_paid_payout_requires_utr(order_with_method, make_payout):
    order, method = order_with_method
    with pytest.raises(IntegrityError), transaction.atomic():
        make_payout(order, method, Decimal('50'), status=Payout.Status.PAID, paid_at=timezone.now())


def test_pending_payout_can_become_paid_only_with_utr(order_with_method, make_payout, admin_user):
    order, method = order_with_method
    payout = make_payout(order, method, Decimal('50'))
    assert payout.utr == ''

    payout.status = Payout.Status.PAID
    payout.paid_at = timezone.now()
    payout.paid_by = admin_user
    with pytest.raises(IntegrityError), transaction.atomic():
        payout.save()

    payout.utr = '123456789012'
    payout.save()


def test_paid_payout_is_frozen(order_with_method, make_payout):
    order, method = order_with_method
    payout = make_payout(
        order, method, Decimal('50'),
        status=Payout.Status.PAID, paid_at=timezone.now(), utr='123456789012',
    )
    payout.utr = '999999999999'
    with pytest.raises(ImmutableFieldError):
        payout.save()

    payout.refresh_from_db()
    payout.status = Payout.Status.FAILED
    with pytest.raises(ImmutableFieldError):
        payout.save()


def test_payout_goes_to_order_payout_method(order_with_method, make_payout, make_method, user):
    order, method = order_with_method
    payout = make_payout(order, None, Decimal('50'), user=None)
    assert payout.payout_method == method
    assert payout.user == user

    other = make_method(user, 'other@ybl')
    with pytest.raises(ValidationError):
        make_payout(order, other, Decimal('50'))


def test_payout_cannot_be_deleted(order_with_method, make_payout):
    order, method = order_with_method
    payout = make_payout(order, method, Decimal('50'))
    with pytest.raises(AppendOnlyError):
        payout.delete()
    with pytest.raises(AppendOnlyError):
        Payout.objects.all().delete()


def test_same_utr_cannot_back_two_paid_payouts(order_with_method, make_payout):
    order, method = order_with_method
    paid = dict(status=Payout.Status.PAID, paid_at=timezone.now(), utr='123456789012')
    make_payout(order, method, Decimal('50'), **paid)
    with pytest.raises(IntegrityError), transaction.atomic():
        make_payout(order, method, Decimal('80'), **paid)


@pytest.mark.parametrize(
    'utr', ['UTR123456789', '12345678901', '1234567890123', '12345678901a', ' 123456789012', '1234 5678901']
)
def test_payout_utr_must_be_12_digits(order_with_method, make_payout, utr):
    order, method = order_with_method
    with pytest.raises(IntegrityError), transaction.atomic():
        make_payout(order, method, Decimal('50'), status=Payout.Status.PAID, paid_at=timezone.now(), utr=utr)
    # Also rejected on a PENDING payout: a stored UTR is always well-formed.
    with pytest.raises(IntegrityError), transaction.atomic():
        make_payout(order, method, Decimal('50'), utr=utr)


def test_payout_utr_keeps_leading_zeros(order_with_method, make_payout):
    order, method = order_with_method
    payout = make_payout(
        order, method, Decimal('50'),
        status=Payout.Status.PAID, paid_at=timezone.now(), utr='000012345678',
    )
    payout.refresh_from_db()
    assert payout.utr == '000012345678'


def test_payout_idempotency_key_is_unique(order_with_method, make_payout):
    order, method = order_with_method
    make_payout(order, method, Decimal('50'), idempotency_key='same')
    with pytest.raises(IntegrityError), transaction.atomic():
        make_payout(order, method, Decimal('50'), idempotency_key='same')


def test_payout_amount_is_immutable(order_with_method, make_payout):
    order, method = order_with_method
    payout = make_payout(order, method, Decimal('50'))
    payout.amount = Decimal('500.00')
    with pytest.raises(ImmutableFieldError):
        payout.save()
