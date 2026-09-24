import re
from decimal import Decimal

import pytest
from django.apps import apps
from django.db import IntegrityError, models, transaction
from django.db.models import ProtectedError

from audit.mixins import AppendOnlyError, ImmutableFieldError
from orders.calculations import calculate_income_amount, calculate_payout_target
from orders.models import Order, OrderOffer

FINANCIAL_APPS = ('paymethods', 'orders', 'payins', 'payouts', 'audit')
MONEY_NAME = re.compile(r'(amount|_total|^payout_target|^balance_|_rate$)')


def _financial_fields():
    return [
        field
        for app in FINANCIAL_APPS
        for model in apps.get_app_config(app).get_models()
        for field in model._meta.get_fields()
        if getattr(field, 'concrete', False)
    ]


def test_no_float_fields_in_financial_apps():
    assert not [f for f in _financial_fields() if isinstance(f, models.FloatField)]


def test_every_money_field_is_decimal():
    money_fields = [f for f in _financial_fields() if MONEY_NAME.search(f.name)]
    assert len(money_fields) >= 12
    for field in money_fields:
        assert isinstance(field, models.DecimalField), field
        assert field.decimal_places == 2, field


def test_money_round_trips_as_decimal(user, make_order):
    order = make_order(user)
    order.refresh_from_db()
    assert order.order_amount == Decimal('255.00')
    assert order.income_rate == Decimal('4.00')
    assert order.income_amount == Decimal('10')
    assert order.payout_target == Decimal('265')
    for name in ('order_amount', 'income_rate', 'income_amount', 'payout_target', 'payout_paid_total'):
        assert isinstance(getattr(order, name), Decimal), name


@pytest.mark.parametrize(
    'order_amount, income_rate',
    [
        (Decimal('0'), Decimal('4.00')),
        (Decimal('-1.00'), Decimal('4.00')),
        (Decimal('255.00'), Decimal('-0.01')),
        (Decimal('255.00'), Decimal('100.01')),
        (Decimal('255.50'), Decimal('4.00')),  # not whole rupees
    ],
)
def test_offer_amount_positive_and_rate_non_negative(db, order_amount, income_rate):
    with pytest.raises(IntegrityError), transaction.atomic():
        OrderOffer.objects.create(order_amount=order_amount, income_rate=income_rate)


@pytest.mark.parametrize(
    'values',
    [
        dict(order_amount=Decimal('0'), income_amount=Decimal('0'), payout_target=Decimal('0')),
        dict(income_rate=Decimal('-4.00')),
        dict(income_amount=Decimal('-10'), payout_target=Decimal('245')),
    ],
)
def test_order_snapshot_values_constrained(user, make_order, values):
    with pytest.raises(IntegrityError), transaction.atomic():
        make_order(user, **values)


@pytest.mark.parametrize(
    'order_amount, expected',
    [
        # order_amount @ 1% gives the raw income directly.
        ('4533', '45'),   # 45.33 -> 45
        ('4566', '46'),   # 45.66 -> 46
        ('4550', '46'),   # 45.50 -> 46 (half up)
        ('4549', '45'),   # 45.49 -> 45
    ],
)
def test_income_rounds_half_up_to_whole_rupees(order_amount, expected):
    assert calculate_income_amount(Decimal(order_amount), Decimal('1.00')) == Decimal(expected)


def test_income_example_255_at_4_percent():
    income = calculate_income_amount(Decimal('255'), Decimal('4.00'))
    assert income == Decimal('10')
    assert calculate_payout_target(Decimal('255'), income) == Decimal('265')


@pytest.mark.parametrize('args', [(255.0, Decimal('4')), (Decimal('255'), 4.0)])
def test_income_calculation_rejects_float(args):
    with pytest.raises(TypeError):
        calculate_income_amount(*args)


def _order_values(order_amount, income_rate, income_amount):
    return dict(
        order_amount=Decimal(order_amount),
        income_rate=Decimal(income_rate),
        income_amount=Decimal(income_amount),
        payout_target=Decimal(order_amount) + Decimal(income_amount),
    )


@pytest.mark.parametrize(
    'order_amount, income_rate, income_amount',
    [
        ('255', '4.00', '10'),          # 10.20 -> 10
        ('1133', '4.00', '45'),         # 45.32 -> 45
        ('1500', '3.04', '46'),         # 45.60 -> 46
        ('250', '5.00', '13'),          # 12.50 -> 13 (half up)
        ('125', '0.40', '1'),           # 0.50 -> 1, float-inexact rate
        ('500', '0.00', '0'),
        ('4999999999', '99.99', '4999499999'),  # 4999499999.0001 -> 4999499999
    ],
)
def test_db_accepts_correctly_rounded_income(user, make_order, order_amount, income_rate, income_amount):
    make_order(user, **_order_values(order_amount, income_rate, income_amount))


@pytest.mark.parametrize(
    'order_amount, income_rate, income_amount',
    [
        ('255', '4.00', '11'),
        ('255', '4.00', '9'),
        ('250', '5.00', '12'),          # half rounded down
        ('1500', '3.04', '45'),
        ('255', '4.00', '10.20'),       # unrounded
    ],
)
def test_db_rejects_wrongly_rounded_income(user, make_order, order_amount, income_rate, income_amount):
    with pytest.raises(IntegrityError), transaction.atomic():
        make_order(user, **_order_values(order_amount, income_rate, income_amount))


def test_calculator_and_db_constraint_agree(user, make_order):
    for order_amount in ('1', '99', '255', '1001', '123457'):
        for income_rate in ('0.00', '0.40', '2.50', '4.00', '4.37', '12.50', '33.33'):
            income = calculate_income_amount(Decimal(order_amount), Decimal(income_rate))
            make_order(user, **_order_values(order_amount, income_rate, income))


@pytest.mark.parametrize('payout_target', ['255', '264', '266', '265.20'])
def test_payout_target_must_equal_amount_plus_income(user, make_order, payout_target):
    with pytest.raises(IntegrityError), transaction.atomic():
        make_order(user, payout_target=Decimal(payout_target))


@pytest.mark.parametrize('income_rate', ['0.00', '100.00'])
def test_offer_rate_bounds_are_inclusive(db, income_rate):
    OrderOffer.objects.create(order_amount=Decimal('255'), income_rate=Decimal(income_rate))


def test_order_rate_above_100_rejected(user, make_order):
    with pytest.raises(IntegrityError), transaction.atomic():
        # 255 x 100.01% = 255.0255 -> 255: consistent income, only the rate is wrong.
        make_order(user, **_order_values('255', '100.01', '255'))


def test_order_rate_of_100_percent_allowed(user, make_order):
    income = calculate_income_amount(Decimal('255'), Decimal('100.00'))
    assert income == Decimal('255')
    make_order(user, **_order_values('255', '100.00', income))


def test_offer_changes_do_not_touch_claimed_orders(user, offer, make_order):
    order = make_order(user)
    offer.order_amount = Decimal('300.00')
    offer.income_rate = Decimal('9.00')
    offer.save()

    order.refresh_from_db()
    assert order.order_amount == Decimal('255.00')
    assert order.income_rate == Decimal('4.00')
    assert order.income_amount == Decimal('10')
    assert order.payout_target == Decimal('265')


@pytest.mark.parametrize(
    'committed, paid',
    [
        (Decimal('266'), Decimal('0')),      # committed over target
        (Decimal('100'), Decimal('101')),    # paid over committed
        (Decimal('0'), Decimal('-1')),       # negative paid
        (Decimal('100.50'), Decimal('0')),   # not whole rupees
    ],
)
def test_order_payout_totals_are_constrained(user, make_order, committed, paid):
    order = make_order(user)
    with pytest.raises(IntegrityError), transaction.atomic():
        Order.objects.filter(pk=order.pk).update(
            payout_committed_total=committed,
            payout_paid_total=paid,
        )


def test_order_totals_may_reach_target_exactly(user, make_order):
    order = make_order(user)
    Order.objects.filter(pk=order.pk).update(
        payout_committed_total=Decimal('265'),
        payout_paid_total=Decimal('265'),
        status=Order.Status.COMPLETED,
    )


def test_completed_order_must_be_fully_paid(user, make_order):
    order = make_order(user)
    with pytest.raises(IntegrityError), transaction.atomic():
        Order.objects.filter(pk=order.pk).update(
            # Order amount paid back but income still owed.
            payout_committed_total=Decimal('255.00'),
            payout_paid_total=Decimal('255.00'),
            status=Order.Status.COMPLETED,
        )


def test_reference_is_public_random_and_unique(user, make_order):
    first, second = make_order(user), make_order(user)
    assert re.fullmatch(r'HW-[0-9A-Z]{10}', first.reference)
    assert first.reference != second.reference
    # Random, not derived from the sequential pk.
    assert first.reference != f'HW-{first.pk:010d}'
    with pytest.raises(IntegrityError), transaction.atomic():
        make_order(user, reference=first.reference)


def test_idempotency_key_unique_per_user(make_user, make_order):
    user, other = make_user(), make_user()
    make_order(user, idempotency_key='same')
    make_order(other, idempotency_key='same')
    with pytest.raises(IntegrityError), transaction.atomic():
        make_order(user, idempotency_key='same')


@pytest.mark.parametrize('field', ['order_amount', 'income_rate', 'income_amount', 'payout_target'])
def test_order_snapshot_values_are_immutable(user, make_order, field):
    order = make_order(user)
    setattr(order, field, Decimal('1.00'))
    with pytest.raises(ImmutableFieldError):
        order.save()


def test_order_requires_payout_method(user, make_order):
    with pytest.raises(IntegrityError), transaction.atomic():
        make_order(user, payout_method=None)


def test_changing_default_method_does_not_change_existing_order(user, make_method, make_order):
    first = make_method(user, 'first@ybl', is_default=True)
    order = make_order(user, payout_method=first)

    first.is_default = False
    first.save()
    make_method(user, 'second@ybl', is_default=True)

    order.refresh_from_db()
    assert order.payout_method == first


def test_order_payout_method_is_fixed(user, make_method, make_order):
    order = make_order(user, payout_method=make_method(user, 'first@ybl'))
    order.payout_method = make_method(user, 'second@ybl')
    with pytest.raises(ImmutableFieldError):
        order.save()


def test_order_cannot_be_deleted(user, make_order):
    order = make_order(user)
    with pytest.raises(AppendOnlyError):
        order.delete()
    with pytest.raises(AppendOnlyError):
        Order.objects.filter(pk=order.pk).delete()


def test_order_status_can_change(user, make_order):
    order = make_order(user)
    order.status = Order.Status.PAYMENT_UNDER_REVIEW
    order.save()


def test_user_and_offer_with_orders_cannot_be_deleted(user, offer, make_order):
    make_order(user)
    with pytest.raises(ProtectedError):
        offer.delete()
    with pytest.raises(ProtectedError):
        user.delete()
