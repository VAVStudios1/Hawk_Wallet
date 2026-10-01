"""API tests for GET /api/admin/dashboard/ (admin operational counts, read-only)."""
from decimal import Decimal

import pytest
from rest_framework.test import APIClient

from conftest import buy_method_for, next_key
from core.models import User
from payins.services import confirm_pay_in, get_pay_in_intent, submit_pay_in_utr
from payouts.services import create_payout, mark_payout_paid
from support.models import Conversation
from support.services import close_conversation

URL = '/api/admin/dashboard/'
_utrs = iter(range(200000000000, 999999999999))

KEYS = {
    'total_users',
    'active_orders',
    'pending_payins',
    'pending_payouts',
    'open_support',
    'orders_awaiting_action',
}


@pytest.fixture
def super_admin(make_user):
    return make_user(role=User.Role.SUPER_ADMIN)


@pytest.fixture
def member(make_user, make_method):
    user = make_user()
    make_method(user, 'member@ybl', is_default=True)
    return user


def _get(user):
    client = APIClient()
    client.force_authenticate(user)
    return client.get(URL)


def _data(user):
    response = _get(user)
    assert response.status_code == 200, response.data
    return response.data['data']


def _submit(member, order):
    intent = get_pay_in_intent(user=member, order_id=order.pk)
    return submit_pay_in_utr(
        user=member, order_id=order.pk, utr=str(next(_utrs)),
        idempotency_key=next_key(), intent_token=intent.intent_token,
        buy_method_id=buy_method_for(member).pk,
    )


def _confirmed_order(member, make_order, admin):
    order = make_order(member)
    confirm_pay_in(admin=admin, payin_id=_submit(member, order).pk, idempotency_key=next_key())
    order.refresh_from_db()
    return order


def _payout(admin, order, amount):
    return create_payout(
        admin=admin, order_id=order.pk, amount=Decimal(amount), idempotency_key=next_key()
    )


def _pay(admin, payout):
    return mark_payout_paid(
        admin=admin, payout_id=payout.pk, utr=str(next(_utrs)), idempotency_key=next_key()
    )


# --- access -------------------------------------------------------------------

def test_admin_gets_dashboard(admin_user):
    data = _data(admin_user)
    assert set(data) == KEYS


def test_super_admin_gets_dashboard(super_admin):
    assert set(_data(super_admin)) == KEYS


@pytest.mark.parametrize('extra', [
    {},                                              # MEMBER
    {'is_verified': False},
    {'role': User.Role.ADMIN, 'is_active': False},
    {'role': User.Role.SUPER_ADMIN, 'is_active': False},
])
def test_non_admin_or_inactive_admin_denied(make_user, extra):
    response = _get(make_user(**extra))
    assert response.status_code == 403
    assert response.data['success'] is False


def test_anonymous_denied():
    assert APIClient().get(URL).status_code == 401


def test_read_only_methods_only(admin_user):
    client = APIClient()
    client.force_authenticate(admin_user)
    assert client.post(URL, {'total_users': 999}).status_code == 405


# --- values -------------------------------------------------------------------

def test_empty_platform_counts(admin_user):
    data = _data(admin_user)
    assert data == {
        'total_users': 0,
        'active_orders': 0,
        'pending_payins': 0,
        'pending_payouts': 0,
        'open_support': 0,
        'orders_awaiting_action': 0,
    }


def test_total_users_counts_members_only(admin_user, super_admin, make_user):
    make_user()
    make_user(is_verified=False)
    make_user(is_active=False)
    make_user(role=User.Role.ADMIN)
    assert _data(admin_user)['total_users'] == 3


def test_order_payin_and_payout_counts(member, make_order, receiving_account, admin_user, super_admin):
    make_order(member)                                          # PAYMENT_REQUIRED
    _submit(member, make_order(member))                         # PAYMENT_UNDER_REVIEW, pay-in UNDER_REVIEW
    _confirmed_order(member, make_order, admin_user)            # ACTIVE
    in_progress = _confirmed_order(member, make_order, admin_user)
    _payout(admin_user, in_progress, '50')                      # PAYOUT_IN_PROGRESS, payout PENDING
    done = _confirmed_order(member, make_order, admin_user)
    _pay(admin_user, _payout(admin_user, done, '265'))          # COMPLETED, payout PAID

    # Awaiting an admin: under review + ACTIVE + PAYOUT_IN_PROGRESS (not PAYMENT_REQUIRED / COMPLETED).
    expected = {'active_orders': 2, 'pending_payins': 1, 'pending_payouts': 1, 'orders_awaiting_action': 3}
    for viewer in (admin_user, super_admin):
        data = _data(viewer)
        assert {key: data[key] for key in expected} == expected


def test_open_support_follows_support_visibility(make_user, admin_user, super_admin):
    other_admin = make_user(role=User.Role.ADMIN)
    Conversation.objects.create(user=make_user(), assigned_admin=admin_user)
    Conversation.objects.create(user=make_user(), assigned_admin=admin_user)
    Conversation.objects.create(user=make_user(), assigned_admin=other_admin)
    closed = Conversation.objects.create(user=make_user(), assigned_admin=admin_user)
    close_conversation(admin=admin_user, conversation_id=closed.public_id)

    assert _data(admin_user)['open_support'] == 2
    assert _data(other_admin)['open_support'] == 1
    assert _data(super_admin)['open_support'] == 3


def test_dashboard_writes_nothing(member, make_order, admin_user):
    from audit.models import AuditEvent, LedgerEntry

    make_order(member)
    before = (AuditEvent.objects.count(), LedgerEntry.objects.count())
    _data(admin_user)
    assert (AuditEvent.objects.count(), LedgerEntry.objects.count()) == before
