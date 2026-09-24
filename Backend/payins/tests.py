from decimal import Decimal

import pytest
from django.core.exceptions import ValidationError
from django.db import IntegrityError, transaction
from django.utils import timezone

from audit.mixins import AppendOnlyError, ImmutableFieldError
from conftest import next_key
from payins.models import PayIn


@pytest.fixture
def make_payin(receiving_account):
    def _make(order, utr='123456789012', **extra):
        values = dict(
            order=order,
            user=order.user,
            amount=order.order_amount,
            receiving_account=receiving_account,
            payee_vpa_snapshot=receiving_account.payee_vpa,
            utr=utr,
            idempotency_key=next_key(),
        )
        values.update(extra)
        return PayIn.objects.create(**values)
    return _make


def _reject(payin):
    payin.status = PayIn.Status.REJECTED
    payin.reviewed_at = timezone.now()
    payin.save()


def test_one_under_review_payin_per_order(user, make_order, make_payin):
    order = make_order(user)
    make_payin(order, utr='111111111111')
    with pytest.raises(IntegrityError), transaction.atomic():
        make_payin(order, utr='222222222222')


def test_resubmission_allowed_after_rejection(user, make_order, make_payin):
    order = make_order(user)
    _reject(make_payin(order, utr='111111111111'))
    make_payin(order, utr='222222222222')


def test_one_confirmed_payin_per_order(user, make_order, make_payin):
    order = make_order(user)
    now = timezone.now()
    make_payin(order, utr='111111111111', status=PayIn.Status.CONFIRMED, reviewed_at=now)
    with pytest.raises(IntegrityError), transaction.atomic():
        make_payin(order, utr='222222222222', status=PayIn.Status.CONFIRMED, reviewed_at=now)


def test_same_utr_cannot_be_active_on_two_orders(make_user, make_order, make_payin):
    make_payin(make_order(make_user()), utr='999999999999')
    with pytest.raises(IntegrityError), transaction.atomic():
        make_payin(make_order(make_user()), utr='999999999999')


def test_rejected_utr_does_not_block_other_payins(make_user, make_order, make_payin):
    _reject(make_payin(make_order(make_user()), utr='999999999999'))
    make_payin(make_order(make_user()), utr='999999999999')


def test_decided_payin_requires_reviewed_at(user, make_order, make_payin):
    with pytest.raises(IntegrityError), transaction.atomic():
        make_payin(make_order(user), status=PayIn.Status.CONFIRMED)


@pytest.mark.parametrize(
    'utr', ['', '12345678901', '1234567890123', 'ABCDEFGHIJKL', '12345678901a', ' 123456789012', '1234-5678901']
)
def test_payin_utr_must_be_12_digits(user, make_order, make_payin, utr):
    with pytest.raises(IntegrityError), transaction.atomic():
        make_payin(make_order(user), utr=utr)


def test_payin_utr_keeps_leading_zeros(user, make_order, make_payin):
    payin = make_payin(make_order(user), utr='000123456789')
    payin.refresh_from_db()
    assert payin.utr == '000123456789'
    assert isinstance(payin.utr, str)


def test_utr_validator_for_full_clean():
    from django.core.exceptions import ValidationError as DjangoValidationError
    from paymethods.validators import utr_validator

    utr_validator('012345678901')
    with pytest.raises(DjangoValidationError):
        utr_validator('12345')


def test_payin_amount_and_user_come_from_order(user, make_order, make_payin):
    order = make_order(user)
    payin = make_payin(order, amount=None, user=None)
    assert payin.amount == order.order_amount == Decimal('255.00')
    assert payin.user == user


@pytest.mark.parametrize(
    'amount', [Decimal('0'), Decimal('100'), Decimal('255.01'), Decimal('265')]
)
def test_payin_amount_must_equal_order_amount(user, make_order, make_payin, amount):
    with pytest.raises(ValidationError):
        make_payin(make_order(user), amount=amount)


def test_payin_user_must_own_order(make_user, make_order, make_payin):
    with pytest.raises(ValidationError):
        make_payin(make_order(make_user()), user=make_user())


def test_rejected_payin_is_frozen(user, make_order, make_payin):
    payin = make_payin(make_order(user))
    _reject(payin)

    payin.status = PayIn.Status.CONFIRMED
    with pytest.raises(ImmutableFieldError):
        payin.save()

    payin.refresh_from_db()
    payin.rejection_reason = 'rewritten'
    with pytest.raises(ImmutableFieldError):
        payin.save()


def test_rejected_payin_kept_when_new_utr_submitted(user, make_order, make_payin):
    order = make_order(user)
    rejected = make_payin(order, utr='111111111111')
    _reject(rejected)
    make_payin(order, utr='222222222222')

    rejected.refresh_from_db()
    assert rejected.utr == '111111111111'
    assert order.pay_ins.count() == 2


def test_payin_cannot_be_deleted(user, make_order, make_payin):
    payin = make_payin(make_order(user))
    with pytest.raises(AppendOnlyError):
        payin.delete()
    with pytest.raises(AppendOnlyError):
        PayIn.objects.all().delete()


def test_payin_idempotency_key_unique_per_user(user, make_order, make_payin):
    make_payin(make_order(user), utr='111111111111', idempotency_key='same')
    with pytest.raises(IntegrityError), transaction.atomic():
        make_payin(make_order(user), utr='222222222222', idempotency_key='same')


def test_payin_amount_and_utr_are_immutable(user, make_order, make_payin):
    payin = make_payin(make_order(user))
    payin.amount = Decimal('1.00')
    with pytest.raises(ImmutableFieldError):
        payin.save()
