"""API tests for the admin read endpoints: all orders, and one order with its pay-in and payout method."""
import pytest
from rest_framework.test import APIClient

from audit.models import AuditEvent, LedgerEntry
from conftest import buy_method_for, next_key
from core.models import User
from orders.models import Order
from payins.models import PayIn

ADMIN_ORDERS_URL = '/api/admin/orders/'


@pytest.fixture
def member(make_user, make_method):
    user = _named(make_user(), 'Asha Member')
    make_method(user, 'member@ybl', is_default=True, label='Main')
    return user


def _named(user, name):
    user.name = name
    user.save(update_fields=['name'])
    return user


def _client(user):
    client = APIClient()
    client.force_authenticate(user)
    return client


def _detail_url(order_id):
    return f'{ADMIN_ORDERS_URL}{order_id}/'


def _claim(client, offer):
    response = client.post(
        '/api/orders/claim/',
        {'offer_id': offer.pk, 'idempotency_key': next_key()},
        format='json',
    )
    assert response.status_code == 201, response.data
    return response.data['data']


def _submit_utr(client, order_id, utr):
    token = client.get(f'/api/orders/{order_id}/pay-in-intent/').data['data']['intent_token']
    response = client.post(
        f'/api/orders/{order_id}/pay-in/',
        {
            'utr': utr, 'idempotency_key': next_key(), 'intent_token': token,
            'buy_method_id': buy_method_for(Order.objects.get(pk=order_id).user).pk,
        },
        format='json',
    )
    assert response.status_code == 201, response.data
    return response.data['data']


# --- access ------------------------------------------------------------------

ADMIN_URLS = [ADMIN_ORDERS_URL, _detail_url(1)]


@pytest.mark.parametrize('url', ADMIN_URLS)
def test_anonymous_rejected(db, url):
    response = APIClient().get(url)
    assert response.status_code == 401
    assert response.data['success'] is False


@pytest.mark.parametrize('url', ADMIN_URLS)
def test_members_forbidden(member, url):
    response = _client(member).get(url)
    assert response.status_code == 403
    assert response.data['success'] is False


@pytest.mark.parametrize('url', ADMIN_URLS)
def test_inactive_admin_rejected(make_user, url):
    admin = make_user(role=User.Role.ADMIN, is_active=False)
    assert _client(admin).get(url).status_code in (401, 403)


@pytest.mark.parametrize('url', ADMIN_URLS)
def test_reads_do_not_accept_writes(admin_user, url):
    assert _client(admin_user).post(url, {}, format='json').status_code == 405


# --- list ----------------------------------------------------------------------

@pytest.mark.parametrize('role', [User.Role.ADMIN, User.Role.SUPER_ADMIN])
def test_list_shows_every_members_orders_newest_first(member, make_user, make_order, role):
    other = _named(make_user(), 'Ravi Member')
    first = make_order(member)
    second = make_order(other)
    admin = make_user(role=role)

    response = _client(admin).get(ADMIN_ORDERS_URL)

    assert response.status_code == 200
    orders = response.data['data']['orders']
    assert [o['id'] for o in orders] == [second.pk, first.pk]
    assert orders[1]['member'] == {'id': member.pk, 'name': 'Asha Member'}
    assert orders[0]['member'] == {'id': other.pk, 'name': 'Ravi Member'}


def test_list_fields_are_stored_values_without_payout_destination(member, admin_user, make_order):
    order = make_order(member)

    data = _client(admin_user).get(ADMIN_ORDERS_URL).data['data']['orders'][0]

    assert data['reference'] == order.reference
    assert data['status'] == Order.Status.PAYMENT_REQUIRED
    assert data['order_amount'] == '255.00'
    assert data['income_amount'] == '10.00'
    assert data['payout_target'] == '265.00'
    assert data['payout_committed_total'] == '0.00'
    assert data['payout_paid_total'] == '0.00'
    # The UPI ID is detail-only, behind an audit event.
    assert 'payout_method' not in data
    assert 'member@ybl' not in str(data)


def test_list_does_not_write_audit_or_ledger(member, admin_user, make_order):
    make_order(member)
    _client(admin_user).get(ADMIN_ORDERS_URL)
    assert AuditEvent.objects.count() == 0
    assert LedgerEntry.objects.count() == 0


def test_list_empty(admin_user):
    response = _client(admin_user).get(ADMIN_ORDERS_URL)
    assert response.status_code == 200
    assert response.data['data']['orders'] == []


# --- detail ------------------------------------------------------------------

def test_detail_shows_order_member_and_payout_method(member, admin_user, make_order):
    order = make_order(member)

    response = _client(admin_user).get(_detail_url(order.pk))

    assert response.status_code == 200
    data = response.data['data']
    assert data['id'] == order.pk
    assert data['member'] == {'id': member.pk, 'name': 'Asha Member'}
    assert data['payout_target'] == '265.00'
    assert data['payout_method']['upi_id'] == 'member@ybl'
    assert data['payout_method']['provider_code'] == 'PHONEPE'
    assert data['payout_method']['label'] == 'Main'
    assert data['latest_pay_in'] is None


def test_detail_records_one_audit_event_and_no_ledger_entry(member, admin_user, make_order):
    order = make_order(member)

    _client(admin_user).get(_detail_url(order.pk))

    event = AuditEvent.objects.get()
    assert event.actor == admin_user
    assert event.action == AuditEvent.Action.PAYOUT_METHOD_VIEWED
    assert event.target_type == 'paymethods.UserPayoutMethod'
    assert event.target_id == str(order.payout_method_id)
    assert event.metadata == {'order_id': order.pk}
    assert 'member@ybl' not in str(event.metadata)
    assert LedgerEntry.objects.count() == 0
    order.refresh_from_db()
    assert order.status == Order.Status.PAYMENT_REQUIRED


def test_detail_unknown_order_is_404_without_audit(admin_user):
    response = _client(admin_user).get(_detail_url(999999))
    assert response.status_code == 404
    assert response.data['errors']['code'] == 'order_not_found'
    assert AuditEvent.objects.count() == 0


def test_member_denied_detail_writes_no_audit(member, make_order):
    order = make_order(member)
    assert _client(member).get(_detail_url(order.pk)).status_code == 403
    assert AuditEvent.objects.count() == 0


def test_detail_shows_rejected_pay_in(member, admin_user, offer, receiving_account):
    client = _client(member)
    order = _claim(client, offer)
    payin = _submit_utr(client, order['id'], '012345678901')
    rejected = _client(admin_user).post(
        f"/api/admin/pay-ins/{payin['id']}/reject/",
        {'rejection_reason': 'UTR not found in bank statement.', 'idempotency_key': next_key()},
        format='json',
    )
    assert rejected.status_code == 200, rejected.data

    data = _client(admin_user).get(_detail_url(order['id'])).data['data']

    latest = data['latest_pay_in']
    assert latest['status'] == PayIn.Status.REJECTED
    assert latest['amount'] == '255.00'
    assert latest['utr'] == '012345678901'
    assert latest['rejection_reason'] == 'UTR not found in bank statement.'
    assert latest['reviewed_at'] is not None
    assert set(latest) == {
        'id', 'status', 'amount', 'utr', 'rejection_reason', 'buy_method',
        'submitted_at', 'reviewed_at', 'updated_at',
    }


def test_detail_shows_buy_and_sell_sides_separately(member, admin_user, offer, receiving_account):
    client = _client(member)
    order = _claim(client, offer)
    buy_method = buy_method_for(member)
    _submit_utr(client, order['id'], '012345678901')

    data = _client(admin_user).get(_detail_url(order['id'])).data['data']

    # SELL: where the platform pays the member.
    assert data['payout_method']['upi_id'] == 'member@ybl'
    # BUY: the member's own account the Pay-In came from.
    assert data['latest_pay_in']['buy_method'] == {
        'id': buy_method.pk,
        'provider_code': 'GOOGLE_PAY',
        'provider_name': 'Google Pay',
        'label': '',
        'upi_id': buy_method.upi_id,
    }


def test_detail_audits_buy_method_view(member, admin_user, offer, receiving_account):
    client = _client(member)
    order = _claim(client, offer)
    payin = _submit_utr(client, order['id'], '012345678901')
    buy_method = buy_method_for(member)

    _client(admin_user).get(_detail_url(order['id']))

    event = AuditEvent.objects.get(action=AuditEvent.Action.BUY_METHOD_VIEWED)
    assert event.actor == admin_user
    assert event.target_type == 'paymethods.UserBuyMethod'
    assert event.target_id == str(buy_method.pk)
    assert event.metadata == {'order_id': order['id'], 'payin_id': payin['id']}
    assert buy_method.upi_id not in str(event.metadata)
    assert AuditEvent.objects.filter(action=AuditEvent.Action.PAYOUT_METHOD_VIEWED).count() == 1
