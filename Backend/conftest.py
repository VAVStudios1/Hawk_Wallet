from decimal import Decimal
from itertools import count

import pytest

from core.models import User
from orders.models import Currency, Order, OrderOffer
from paymethods.models import (
    PaymentProvider,
    PlatformReceivingAccount,
    UserBuyMethod,
    UserPayoutMethod,
)

_mobile_numbers = count(9000000000)
_keys = count(1)


@pytest.fixture(autouse=True)
def _no_real_sms(settings, monkeypatch):
    # .env may select the live 2Factor provider; tests must never reach it.
    settings.SMS_PROVIDER = 'console'
    settings.TWOFACTOR_API_KEY = ''

    def _network_disabled(request, timeout):
        raise RuntimeError('Network access is disabled in tests.')

    monkeypatch.setattr('core.sms._open', _network_disabled)


def next_key():
    return f'key-{next(_keys)}'


def buy_method_for(user):
    """
    The user's active PERSONAL / BUY method (the account a Pay-In is paid
    from), creating a default GOOGLE_PAY one if they have none.
    """
    method = (
        UserBuyMethod.objects.filter(user=user, is_active=True).order_by('-is_default', 'id').first()
    )
    if method is None:
        method = UserBuyMethod.objects.create(
            user=user,
            provider=PaymentProvider.objects.get(code='GOOGLE_PAY'),
            upi_id=f'buyer{user.pk}@okaxis',
            is_default=True,
        )
    return method


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
    # PHONEPE is seeded by paymethods.0002 (and made a PERSONAL / BUY
    # provider by 0004); this test copy is re-enabled for payout methods and
    # pay-ins, as the SELL-side tests expect, and so must be off for BUY.
    provider, _ = PaymentProvider.objects.update_or_create(
        code='PHONEPE',
        defaults=dict(
            display_name='PhonePe',
            enabled_for_payout_methods=True,
            enabled_for_buy_methods=False,
            enabled_for_pay_in=True,
            is_active=True,
        ),
    )
    return provider


@pytest.fixture
def buy_provider(db):
    # GOOGLE_PAY: a PERSONAL / BUY provider as seeded by paymethods.0004.
    return PaymentProvider.objects.get(code='GOOGLE_PAY', enabled_for_buy_methods=True)


@pytest.fixture
def make_buy_method(buy_provider):
    def _make(user, upi_id='user@okaxis', **extra):
        return UserBuyMethod.objects.create(
            user=user, provider=buy_provider, upi_id=upi_id, **extra
        )
    return _make


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
