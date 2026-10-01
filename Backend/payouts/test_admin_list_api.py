"""API tests for GET /api/admin/payouts/ and /api/admin/payouts/{id}/ (admin payout list and detail)."""
from decimal import Decimal

import pytest
from rest_framework.test import APIClient

from audit.models import AuditEvent
from conftest import buy_method_for, next_key
from core.models import User
from payins.services import confirm_pay_in, get_pay_in_intent, submit_pay_in_utr
from payouts.models import Payout
from payouts.services import cancel_payout, create_payout

URL = '/api/admin/payouts/'
_utrs = iter(range(400000000000, 999999999999))


@pytest.fixture
def super_admin(make_user):
    return make_user(role=User.Role.SUPER_ADMIN)


@pytest.fixture
def member(make_user, make_method):
    user = _named(make_user, 'Ravi Shah')
    make_method(user, 'ravi@ybl', is_default=True)
    return user


def _client(user):
    client = APIClient()
    client.force_authenticate(user)
    return client


def _data(user, url=URL, **params):
    response = _client(user).get(url, params)
    assert response.status_code == 200, response.data
    return response.data['data']


def _confirmed_order(member, make_order, admin):
    order = make_order(member)
    intent = get_pay_in_intent(user=member, order_id=order.pk)
    payin = submit_pay_in_utr(
        user=member, order_id=order.pk, utr=str(next(_utrs)),
        idempotency_key=next_key(), intent_token=intent.intent_token,
        buy_method_id=buy_method_for(member).pk,
    )
    confirm_pay_in(admin=admin, payin_id=payin.pk, idempotency_key=next_key())
    order.refresh_from_db()
    return order


def _payout(admin, order, amount='50'):
    return create_payout(admin=admin, order_id=order.pk, amount=Decimal(amount), idempotency_key=next_key())


def _named(make_user, name, **extra):
    user = make_user(**extra)
    user.name = name
    user.save(update_fields=['name'])
    return user


# --- access -------------------------------------------------------------------

def test_admin_and_super_admin_can_list(admin_user, super_admin):
    for viewer in (admin_user, super_admin):
        assert set(_data(viewer)) == {'payouts', 'page', 'page_size', 'total', 'has_next'}


def test_member_forbidden_and_anonymous_rejected(member, admin_user, make_order, receiving_account):
    payout = _payout(admin_user, _confirmed_order(member, make_order, admin_user))
    for url in (URL, f'{URL}{payout.pk}/'):
        assert _client(member).get(url).status_code == 403
        assert APIClient().get(url).status_code == 401


# --- list ---------------------------------------------------------------------

def test_list_has_provider_but_no_upi(member, admin_user, make_order, receiving_account):
    order = _confirmed_order(member, make_order, admin_user)
    payout = _payout(admin_user, order)

    item = _data(admin_user)['payouts'][0]
    assert item['id'] == payout.pk
    assert item['status'] == 'PENDING'
    assert item['amount'] == '50.00'
    assert item['provider_code'] == payout.provider_code_snapshot
    assert item['order']['reference'] == order.reference
    assert item['order']['payout_target'] == '265.00'
    assert item['order']['payout_committed_total'] == '50.00'
    assert item['order']['payout_paid_total'] == '0.00'
    assert item['order']['remaining_to_commit'] == '215.00'
    assert item['member']['mobile_number_masked'].endswith(member.mobile_number[-4:])
    assert 'ravi@ybl' not in str(item)
    assert member.mobile_number not in str(item)
    assert AuditEvent.objects.filter(action=AuditEvent.Action.PAYOUT_METHOD_VIEWED).count() == 0


def test_status_filter_search_and_ordering(member, admin_user, make_order, receiving_account):
    order = _confirmed_order(member, make_order, admin_user)
    older = _payout(admin_user, order, '50')
    newer = _payout(admin_user, order, '60')
    cancel_payout(admin=admin_user, payout_id=older.pk, cancellation_reason='Typo', idempotency_key=next_key())

    assert [p['id'] for p in _data(admin_user, status='PENDING')['payouts']] == [newer.pk]
    assert [p['id'] for p in _data(admin_user, status='CANCELLED')['payouts']] == [older.pk]
    assert _data(admin_user, status='FAILED')['payouts'] == []
    assert [p['id'] for p in _data(admin_user)['payouts']] == [newer.pk, older.pk]
    assert [p['id'] for p in _data(admin_user, ordering='created_at')['payouts']] == [older.pk, newer.pk]
    assert _data(admin_user, search=order.reference)['total'] == 2
    assert _data(admin_user, search='ravi')['total'] == 2
    assert _data(admin_user, search='nobody')['total'] == 0


@pytest.mark.parametrize('params', [
    {'status': 'Paid'},
    {'ordering': '-amount'},
    {'page': 3},
    {'page_size': -1},
])
def test_invalid_queries_rejected(admin_user, params):
    response = _client(admin_user).get(URL, params)
    assert response.status_code == 400
    assert response.data['errors']['code'] == 'invalid_query'


# --- detail -------------------------------------------------------------------

def test_detail_shows_destination_and_audits(member, admin_user, make_order, receiving_account):
    order = _confirmed_order(member, make_order, admin_user)
    payout = _payout(admin_user, order)

    data = _data(admin_user, f'{URL}{payout.pk}/')
    assert data['upi_id'] == 'ravi@ybl'
    assert data['utr'] == ''
    assert data['cancellation_reason'] == ''
    assert data['created_by_name'] == admin_user.name
    assert data['paid_by_name'] is None
    event = AuditEvent.objects.get(action=AuditEvent.Action.PAYOUT_METHOD_VIEWED)
    assert event.actor == admin_user
    assert event.metadata == {'payout_id': payout.pk, 'order_id': order.pk}


def test_detail_shows_cancellation_reason(member, admin_user, make_order, receiving_account):
    payout = _payout(admin_user, _confirmed_order(member, make_order, admin_user))
    cancel_payout(admin=admin_user, payout_id=payout.pk, cancellation_reason='Wrong amount', idempotency_key=next_key())
    data = _data(admin_user, f'{URL}{payout.pk}/')
    assert data['status'] == 'CANCELLED'
    assert data['cancellation_reason'] == 'Wrong amount'


def test_detail_unknown_is_404_without_audit(admin_user):
    response = _client(admin_user).get(f'{URL}999999/')
    assert response.status_code == 404
    assert response.data['errors']['code'] == 'payout_not_found'
    assert AuditEvent.objects.count() == 0


def test_existing_mark_paid_endpoint_still_works(member, super_admin, admin_user, make_order, receiving_account):
    payout = _payout(admin_user, _confirmed_order(member, make_order, admin_user))
    response = _client(super_admin).post(
        f'{URL}{payout.pk}/paid/', {'utr': str(next(_utrs)), 'idempotency_key': next_key()}, format='json'
    )
    assert response.status_code == 200, response.data
    payout.refresh_from_db()
    assert payout.status == Payout.Status.PAID
    assert _data(admin_user, f'{URL}{payout.pk}/')['paid_by_name'] == super_admin.name
