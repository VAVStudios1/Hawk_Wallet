"""API tests for the member read endpoints: available offers, own orders, order detail."""
from datetime import timedelta
from decimal import Decimal

import pytest
from django.utils import timezone
from rest_framework.test import APIClient

from conftest import buy_method_for, next_key
from core.models import User
from orders.models import Currency, Order, OrderOffer
from payins.models import PayIn

OFFERS_URL = '/api/orders/offers/'
ORDERS_URL = '/api/orders/'


@pytest.fixture
def member(make_user, make_method):
    user = make_user()
    make_method(user, 'member@ybl', is_default=True)
    return user


def _client(user):
    client = APIClient()
    client.force_authenticate(user)
    return client


def _detail_url(order_id):
    return f'{ORDERS_URL}{order_id}/'


def _make_offer(**extra):
    values = dict(
        currency=Currency.INR,
        order_amount=Decimal('255.00'),
        income_rate=Decimal('4.00'),
        is_active=True,
    )
    values.update(extra)
    return OrderOffer.objects.create(**values)


def _offer_ids(response):
    return [offer['id'] for offer in response.data['data']['offers']]


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

READ_URLS = [OFFERS_URL, ORDERS_URL, _detail_url(1)]


@pytest.mark.parametrize('url', READ_URLS)
def test_anonymous_rejected(db, url):
    response = APIClient().get(url)
    assert response.status_code == 401
    assert response.data['success'] is False


@pytest.mark.parametrize('url', READ_URLS)
@pytest.mark.parametrize('role', [User.Role.ADMIN, User.Role.SUPER_ADMIN])
def test_admin_roles_forbidden(make_user, url, role):
    # Same convention as claim / pay-in: these are member-only endpoints.
    response = _client(make_user(role=role)).get(url)
    assert response.status_code == 403
    assert response.data['success'] is False


@pytest.mark.parametrize('url', READ_URLS)
def test_unverified_member_forbidden(make_user, url):
    response = _client(make_user(is_verified=False)).get(url)
    assert response.status_code == 403


@pytest.mark.parametrize('url', [OFFERS_URL, ORDERS_URL])
def test_reads_do_not_accept_writes(member, url):
    assert _client(member).post(url, {}, format='json').status_code == 405


# --- available offers ----------------------------------------------------------

def test_offers_filtered_to_claimable(member):
    now = timezone.now()
    open_offer = _make_offer()
    in_window = _make_offer(valid_from=now - timedelta(hours=1), valid_to=now + timedelta(hours=1))
    with_stock = _make_offer(quantity_available=1)
    _make_offer(is_active=False)
    _make_offer(valid_to=now - timedelta(seconds=1))
    _make_offer(valid_from=now + timedelta(hours=1))
    _make_offer(quantity_available=0)

    response = _client(member).get(OFFERS_URL)

    assert response.status_code == 200, response.data
    assert sorted(_offer_ids(response)) == sorted([open_offer.pk, in_window.pk, with_stock.pk])


def test_offer_sold_out_by_claim_disappears(member):
    offer = _make_offer(quantity_available=1)
    client = _client(member)
    assert _offer_ids(client.get(OFFERS_URL)) == [offer.pk]

    _claim(client, offer)

    assert _offer_ids(client.get(OFFERS_URL)) == []


def test_offer_fields_and_server_computed_values(member):
    valid_to = timezone.now() + timedelta(days=1)
    _make_offer(valid_to=valid_to, quantity_available=5)

    offer = _client(member).get(OFFERS_URL).data['data']['offers'][0]

    # 255 x 4% = 10.20 -> 10 (whole rupees, half-up); target = 255 + 10.
    assert offer['currency'] == 'INR'
    assert offer['order_amount'] == '255.00'
    assert offer['income_rate'] == '4.00'
    assert offer['income_amount'] == '10.00'
    assert offer['payout_target'] == '265.00'
    assert offer['valid_to'] is not None
    # Admin-side stock, activity flags and timestamps are not exposed.
    assert set(offer) == {
        'id', 'currency', 'order_amount', 'income_rate',
        'income_amount', 'payout_target', 'valid_to',
    }


@pytest.mark.parametrize(
    'order_amount, income_rate, income, target',
    [
        ('255.00', '4.00', '10.00', '265.00'),
        ('150.00', '3.00', '5.00', '155.00'),     # 4.50 rounds half-up to 5
        ('1000.00', '2.25', '23.00', '1023.00'),  # 22.50 rounds half-up to 23
        ('500.00', '0.00', '0.00', '500.00'),
    ],
)
def test_offer_preview_matches_claimed_order(member, order_amount, income_rate, income, target):
    offer = _make_offer(order_amount=Decimal(order_amount), income_rate=Decimal(income_rate))
    client = _client(member)

    preview = client.get(OFFERS_URL).data['data']['offers'][0]
    order = _claim(client, offer)

    assert (preview['income_amount'], preview['payout_target']) == (income, target)
    assert (order['income_amount'], order['payout_target']) == (income, target)


def test_offers_empty_list(member):
    response = _client(member).get(OFFERS_URL)
    assert response.status_code == 200
    assert response.data['data'] == {'offers': []}


# --- own orders ---------------------------------------------------------------

def test_orders_lists_only_own_orders_newest_first(member, make_user, make_order):
    older = make_order(member, claimed_at=timezone.now() - timedelta(hours=1))
    newer = make_order(member)
    make_order(make_user())

    response = _client(member).get(ORDERS_URL)

    assert response.status_code == 200, response.data
    orders = response.data['data']['orders']
    assert [order['id'] for order in orders] == [newer.pk, older.pk]
    first = orders[0]
    assert first['reference'] == newer.reference
    assert first['status'] == Order.Status.PAYMENT_REQUIRED
    assert first['order_amount'] == '255.00'
    assert first['income_amount'] == '10.00'
    assert first['payout_target'] == '265.00'
    assert first['payout_method']['upi_id'] == 'member@ybl'
    assert 'user' not in first and 'idempotency_key' not in first


def test_orders_empty_list(member):
    response = _client(member).get(ORDERS_URL)
    assert response.status_code == 200
    assert response.data['data'] == {'orders': []}


# --- order detail ---------------------------------------------------------------

def test_order_detail_returns_own_order(member, make_order):
    order = make_order(member)

    response = _client(member).get(_detail_url(order.pk))

    assert response.status_code == 200, response.data
    data = response.data['data']
    assert data['id'] == order.pk
    assert data['reference'] == order.reference
    assert data['order_amount'] == '255.00'
    assert data['payout_target'] == '265.00'
    assert data['payout_method']['provider_code'] == 'PHONEPE'
    assert data['latest_pay_in'] is None
    assert 'user' not in data and 'idempotency_key' not in data


def test_other_members_order_looks_missing(member, make_user, make_order):
    theirs = make_order(make_user())
    client = _client(member)

    other = client.get(_detail_url(theirs.pk))
    missing = client.get(_detail_url(999999))

    assert other.status_code == missing.status_code == 404
    assert other.data == missing.data
    assert missing.data['errors']['code'] == 'order_not_found'


def test_order_detail_shows_rejected_then_resubmitted_pay_in(
    member, admin_user, offer, receiving_account
):
    client = _client(member)
    order = _claim(client, offer)
    payin = _submit_utr(client, order['id'], '012345678901')

    rejected = _client(admin_user).post(
        f"/api/admin/pay-ins/{payin['id']}/reject/",
        {'rejection_reason': 'UTR not found in bank statement.', 'idempotency_key': next_key()},
        format='json',
    )
    assert rejected.status_code == 200, rejected.data

    data = client.get(_detail_url(order['id'])).data['data']
    assert data['status'] == Order.Status.PAYMENT_REQUIRED
    latest = data['latest_pay_in']
    assert latest['status'] == PayIn.Status.REJECTED
    assert latest['utr'] == '012345678901'
    assert latest['amount'] == '255.00'
    assert latest['rejection_reason'] == 'UTR not found in bank statement.'
    # Reviewer and receiving-account internals stay admin-side.
    assert set(latest) == {
        'id', 'status', 'amount', 'utr', 'rejection_reason', 'buy_method',
        'submitted_at', 'updated_at',
    }

    _submit_utr(client, order['id'], '112345678901')

    data = client.get(_detail_url(order['id'])).data['data']
    assert data['status'] == Order.Status.PAYMENT_UNDER_REVIEW
    assert data['latest_pay_in']['status'] == PayIn.Status.UNDER_REVIEW
    assert data['latest_pay_in']['utr'] == '112345678901'
    assert data['latest_pay_in']['rejection_reason'] == ''
