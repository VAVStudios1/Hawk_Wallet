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


# --- available offers: order_amount filter --------------------------------------

FILTER_AMOUNTS = ['0.00', '1.00', '999.00', '1000.00', '4999.00', '5000.00',
                  '7999.00', '8000.00', '9999.00', '10000.00', '25000.00']


@pytest.fixture
def amount_offers(member):
    """One claimable offer per FILTER_AMOUNTS entry except 0 (amounts are positive)."""
    return {
        amount: _make_offer(order_amount=Decimal(amount))
        for amount in FILTER_AMOUNTS if amount != '0.00'
    }


def _filtered_amounts(member, query):
    response = _client(member).get(OFFERS_URL, query)
    assert response.status_code == 200, response.data
    return [offer['order_amount'] for offer in response.data['data']['offers']]


def test_offer_filter_absent_returns_all_in_existing_order(member, amount_offers):
    response = _client(member).get(OFFERS_URL)

    assert response.status_code == 200
    assert set(response.data['data']) == {'offers'}
    assert [o['order_amount'] for o in response.data['data']['offers']] == [
        '1.00', '999.00', '1000.00', '4999.00', '5000.00', '7999.00',
        '8000.00', '9999.00', '10000.00', '25000.00',
    ]


@pytest.mark.parametrize(
    'query, expected',
    [
        # min only; the exact lower bound is included.
        ({'min_amount': '8000'}, ['8000.00', '9999.00', '10000.00', '25000.00']),
        # max only; the exact upper bound is included.
        ({'max_amount': '999'}, ['1.00', '999.00']),
        # both, both bounds inclusive.
        ({'min_amount': '5000', 'max_amount': '7999'}, ['5000.00', '7999.00']),
        # Android presets.
        ({'min_amount': '0', 'max_amount': '999'}, ['1.00', '999.00']),
        ({'min_amount': '1000', 'max_amount': '2999'}, ['1000.00']),
        ({'min_amount': '3000', 'max_amount': '4999'}, ['4999.00']),
        ({'min_amount': '8000', 'max_amount': '9999'}, ['8000.00', '9999.00']),
        ({'min_amount': '10000'}, ['10000.00', '25000.00']),
        # min == max is a single-amount range; leading zeros are plain digits.
        ({'min_amount': '5000', 'max_amount': '5000'}, ['5000.00']),
        ({'min_amount': '0005000', 'max_amount': '5000'}, ['5000.00']),
        # Valid range with nothing in it: empty list, not an error.
        ({'min_amount': '2000', 'max_amount': '2999'}, []),
        # The largest allowed value.
        ({'max_amount': '9999999999'}, ['1.00', '999.00', '1000.00', '4999.00', '5000.00',
                                        '7999.00', '8000.00', '9999.00', '10000.00', '25000.00']),
    ],
)
def test_offer_filter_by_order_amount_inclusive(member, amount_offers, query, expected):
    assert _filtered_amounts(member, query) == expected


def test_offer_filter_keeps_response_shape(member, amount_offers):
    response = _client(member).get(OFFERS_URL, {'min_amount': '5000', 'max_amount': '5000'})

    offer = response.data['data']['offers'][0]
    assert set(response.data['data']) == {'offers'}
    # income_amount / payout_target still come from the server, unfiltered on.
    assert offer['id'] == amount_offers['5000.00'].pk
    assert set(offer) == {
        'id', 'currency', 'order_amount', 'income_rate',
        'income_amount', 'payout_target', 'valid_to',
    }


def test_offer_filter_is_on_order_amount_not_payout_target(member):
    # 9800 @ 4% -> income 392, target 10192: "You pay" is below 10000.
    offer = _make_offer(order_amount=Decimal('9800.00'), income_rate=Decimal('4.00'))
    client = _client(member)

    assert _offer_ids(client.get(OFFERS_URL, {'min_amount': '10000'})) == []
    assert _offer_ids(client.get(OFFERS_URL, {'min_amount': '8000', 'max_amount': '9999'})) == [offer.pk]


@pytest.mark.parametrize(
    'query',
    [
        {'min_amount': '8000', 'max_amount': '7999'},  # min > max
        {'min_amount': 'abc'},                          # non-numeric
        {'max_amount': '5k'},
        {'min_amount': '-1'},                           # negative
        {'max_amount': '-5000'},
        {'min_amount': '5000.00'},                      # decimal
        {'max_amount': '999.5'},
        {'min_amount': '1e3'},
        {'min_amount': '10000000000'},                  # 11 digits: too large
        {'max_amount': '99999999999999999999'},
        {'min_amount': ''},                             # blank
        {'max_amount': ' 500'},                         # whitespace
        {'min_amount': '+500'},                         # sign
        {'min_amount': '５００'},                        # non-ASCII digits
    ],
)
def test_offer_filter_invalid_values_rejected(member, amount_offers, query):
    response = _client(member).get(OFFERS_URL, query)

    assert response.status_code == 400
    assert response.data['success'] is False
    assert response.data['errors'] == {'code': 'invalid_amount_filter'}
    assert 'data' not in response.data


def test_offer_filter_does_not_expose_unclaimable_offers(member):
    now = timezone.now()
    claimable = _make_offer(order_amount=Decimal('6000.00'))
    _make_offer(order_amount=Decimal('6000.00'), is_active=False)
    _make_offer(order_amount=Decimal('6000.00'), valid_to=now - timedelta(seconds=1))
    _make_offer(order_amount=Decimal('6000.00'), valid_from=now + timedelta(hours=1))
    _make_offer(order_amount=Decimal('6000.00'), quantity_available=0)

    response = _client(member).get(OFFERS_URL, {'min_amount': '5000', 'max_amount': '7999'})

    assert _offer_ids(response) == [claimable.pk]


def test_offer_filter_still_member_only(make_user):
    response = _client(make_user(role=User.Role.ADMIN)).get(OFFERS_URL, {'min_amount': '0'})
    assert response.status_code == 403


def test_offer_filter_does_not_affect_my_orders(member):
    client = _client(member)
    order = _claim(client, _make_offer(order_amount=Decimal('255.00')))

    # /api/orders/ ignores the offer filter params, valid or not.
    for query in ({'min_amount': '10000'}, {'max_amount': '1'}, {'min_amount': 'abc'}):
        response = client.get(ORDERS_URL, query)
        assert response.status_code == 200, response.data
        assert [o['id'] for o in response.data['data']['orders']] == [order['id']]


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
