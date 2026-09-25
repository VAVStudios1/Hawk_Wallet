"""API tests for GET /api/home/ (member dashboard, read-only)."""
from decimal import Decimal

import pytest
from rest_framework.test import APIClient

from audit.models import AuditEvent, LedgerEntry
from conftest import next_key
from core.models import User
from orders.models import Order
from payins.models import PayIn
from payins.services import confirm_pay_in, get_pay_in_intent, reject_pay_in, submit_pay_in_utr
from payouts.models import Payout
from payouts.services import create_payout, mark_payout_paid

URL = '/api/home/'
_utrs = iter(range(100000000000, 999999999999))


@pytest.fixture
def member(make_user, make_method):
    user = make_user()
    user.name = 'Lucky'
    user.save(update_fields=['name'])
    make_method(user, 'member@ybl', is_default=True)
    return user


def _home(user):
    client = APIClient()
    client.force_authenticate(user)
    return client.get(URL)


def _data(user):
    response = _home(user)
    assert response.status_code == 200, response.data
    return response.data['data']


def _submit(member, order):
    intent = get_pay_in_intent(user=member, order_id=order.pk)
    return submit_pay_in_utr(
        user=member, order_id=order.pk, utr=str(next(_utrs)),
        idempotency_key=next_key(), intent_token=intent.intent_token,
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

def test_verified_member_gets_home(member):
    data = _data(member)
    assert data['user'] == {'id': member.pk, 'name': 'Lucky'}
    assert set(data) == {'user', 'balance', 'summary', 'deposit_withdrawal', 'recent_transactions'}


@pytest.mark.parametrize('extra', [
    {'is_verified': False},
    {'is_active': False},
    {'role': User.Role.ADMIN},
    {'role': User.Role.SUPER_ADMIN},
])
def test_non_member_or_unverified_denied(make_user, extra):
    response = _home(make_user(**extra))
    assert response.status_code == 403
    assert response.data['success'] is False


def test_anonymous_denied():
    assert APIClient().get(URL).status_code == 401


# --- values -------------------------------------------------------------------

def test_new_member_gets_zeros(member):
    data = _data(member)
    assert data['balance'] == {'available': '0.00', 'pending': '0.00'}
    assert data['summary'] == {'total_earnings': '0.00', 'active_orders': 0, 'pending_payouts': '0.00'}
    assert data['deposit_withdrawal'] == {'deposit': '0.00', 'withdrawal': '0.00'}
    assert data['recent_transactions'] == []


def test_only_own_data(member, make_user, make_method, make_order, receiving_account, admin_user):
    other = make_user()
    make_method(other, 'other@ybl', is_default=True)
    order = _confirmed_order(other, make_order, admin_user)
    _pay(admin_user, _payout(admin_user, order, '100'))

    data = _data(member)
    assert data['balance'] == {'available': '0.00', 'pending': '0.00'}
    assert data['deposit_withdrawal'] == {'deposit': '0.00', 'withdrawal': '0.00'}
    assert data['summary']['active_orders'] == 0
    assert data['recent_transactions'] == []
    # And the other member sees their own activity.
    assert _data(other)['deposit_withdrawal']['withdrawal'] == '100.00'


def test_confirmed_payin(member, make_order, receiving_account, admin_user):
    order = _confirmed_order(member, make_order, admin_user)

    data = _data(member)
    assert data['deposit_withdrawal'] == {'deposit': '255.00', 'withdrawal': '0.00'}
    assert data['balance'] == {'available': '265.00', 'pending': '0.00'}
    assert data['summary'] == {'total_earnings': '0.00', 'active_orders': 1, 'pending_payouts': '0.00'}
    [item] = data['recent_transactions']
    assert item['type'] == 'ORDER_PAYMENT' and item['status'] == 'SUCCESS'
    assert item['amount'] == '-255.00' and item['reference'] == order.reference


def test_under_review_and_rejected_payins_are_not_deposits(member, make_order, receiving_account, admin_user):
    rejected = make_order(member)
    reject_pay_in(
        admin=admin_user, payin_id=_submit(member, rejected).pk,
        rejection_reason='No such UTR', idempotency_key=next_key(),
    )
    _submit(member, make_order(member))     # stays UNDER_REVIEW

    data = _data(member)
    assert data['deposit_withdrawal']['deposit'] == '0.00'
    assert data['balance'] == {'available': '0.00', 'pending': '0.00'}
    assert data['summary']['active_orders'] == 0
    statuses = sorted(item['status'] for item in data['recent_transactions'])
    assert statuses == ['PENDING', 'REJECTED']


def test_pending_payout_is_not_a_withdrawal(member, make_order, receiving_account, admin_user):
    order = _confirmed_order(member, make_order, admin_user)
    _payout(admin_user, order, '100')

    data = _data(member)
    assert data['balance'] == {'available': '165.00', 'pending': '100.00'}
    assert data['summary']['pending_payouts'] == '100.00'
    assert data['deposit_withdrawal']['withdrawal'] == '0.00'
    assert data['recent_transactions'][0]['type'] == 'PAYOUT'
    assert data['recent_transactions'][0]['status'] == 'PENDING'


def test_paid_payouts(member, make_order, receiving_account, admin_user):
    order = _confirmed_order(member, make_order, admin_user)
    _pay(admin_user, _payout(admin_user, order, '100'))

    data = _data(member)
    assert data['balance'] == {'available': '165.00', 'pending': '0.00'}
    assert data['deposit_withdrawal']['withdrawal'] == '100.00'
    assert data['summary']['total_earnings'] == '0.00'      # not completed yet
    assert data['recent_transactions'][0] == {**data['recent_transactions'][0],
                                             'type': 'PAYOUT', 'status': 'SUCCESS', 'amount': '100.00'}

    _pay(admin_user, _payout(admin_user, order, '165'))     # completes the order

    data = _data(member)
    assert data['balance'] == {'available': '0.00', 'pending': '0.00'}
    assert data['deposit_withdrawal']['withdrawal'] == '265.00'
    assert data['summary'] == {'total_earnings': '10.00', 'active_orders': 0, 'pending_payouts': '0.00'}


def test_active_order_count(member, make_order, receiving_account, admin_user):
    make_order(member)                                          # PAYMENT_REQUIRED
    _confirmed_order(member, make_order, admin_user)            # ACTIVE
    in_progress = _confirmed_order(member, make_order, admin_user)
    _payout(admin_user, in_progress, '50')                      # PAYOUT_IN_PROGRESS
    done = _confirmed_order(member, make_order, admin_user)
    _pay(admin_user, _payout(admin_user, done, '265'))          # COMPLETED

    data = _data(member)
    assert data['summary']['active_orders'] == 2
    # 265 + (265 - 50) owed but uncommitted; 50 committed.
    assert data['balance'] == {'available': '480.00', 'pending': '50.00'}


def test_recent_transactions_limited_to_five(member, make_order, receiving_account, admin_user):
    for _ in range(4):
        order = _confirmed_order(member, make_order, admin_user)
        _pay(admin_user, _payout(admin_user, order, '10'))

    items = _data(member)['recent_transactions']
    assert len(items) == 5
    times = [item['created_at'] for item in items]
    assert times == sorted(times, reverse=True)


def test_get_changes_no_financial_state(member, make_order, receiving_account, admin_user):
    order = _confirmed_order(member, make_order, admin_user)
    _pay(admin_user, _payout(admin_user, order, '100'))
    _payout(admin_user, order, '50')

    def snapshot():
        return [
            list(model.objects.order_by('pk').values())
            for model in (Order, PayIn, Payout, LedgerEntry, AuditEvent)
        ]

    before = snapshot()
    _data(member)
    _data(member)
    assert snapshot() == before
