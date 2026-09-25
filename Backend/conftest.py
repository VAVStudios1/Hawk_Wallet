from decimal import Decimal
from itertools import count

import pytest

from core.models import User
from orders.models import Currency, Order, OrderOffer
from paymethods.models import PaymentProvider, PlatformReceivingAccount, UserPayoutMethod

_mobile_numbers = count(9000000000)
_keys = count(1)


def next_key():
    return f'key-{next(_keys)}'


@pytest.fixture
def make_user(db):
    def _make(**extra):
        extra.setdefault('is_verified', True)
        return User.objects.create_user(
            mobile_number=str(next(_mobile_numbers)),
            name='Test User',
            password='unused-password-123',
            **extra,
        )
    return _make


@pytest.fixture
def user(make_user):
    return make_user()


@pytest.fixture
def admin_user(make_user):
    return make_user(role=User.Role.ADMIN)


@pytest.fixture
def provider(db):
    return PaymentProvider.objects.create(
        code='PHONEPE',
        display_name='PhonePe',
        enabled_for_payout_methods=True,
        enabled_for_pay_in=True,
    )


@pytest.fixture
def make_method(provider):
    def _make(user, upi_id='user@ybl', **extra):
        # A stored name, not an upload: model tests never touch disk.
        extra.setdefault('qr_image', 'payout_qr/test.png')
        return UserPayoutMethod.objects.create(user=user, provider=provider, upi_id=upi_id, **extra)
    return _make


@pytest.fixture
def receiving_account(provider):
    return PlatformReceivingAccount.objects.create(
        provider=provider,
        payee_vpa='platform@ybl',
        payee_name='Hawk Wallet',
        is_active=True,
    )


@pytest.fixture
def offer(db):
    return OrderOffer.objects.create(
        currency=Currency.INR,
        order_amount=Decimal('255.00'),
        income_rate=Decimal('4.00'),
        is_active=True,
    )


@pytest.fixture
def make_order(offer, make_method):
    def _make(user, **extra):
        if 'payout_method' not in extra:
            extra['payout_method'] = (
                user.payout_methods.filter(is_active=True).first() or make_method(user)
            )
        # Snapshot values for the 255 @ 4.00% offer, written out literally
        # (255 x 4% = 10.20 -> 10) so fixtures don't depend on the calculator.
        values = dict(
            user=user,
            offer=offer,
            currency=offer.currency,
            order_amount=offer.order_amount,
            income_rate=offer.income_rate,
            income_amount=Decimal('10'),
            payout_target=Decimal('265'),
            idempotency_key=next_key(),
        )
        values.update(extra)
        return Order.objects.create(**values)
    return _make
