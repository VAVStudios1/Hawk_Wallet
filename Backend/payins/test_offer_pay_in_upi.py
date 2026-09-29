"""
Offer-specific Pay-In UPI: the admin attaches it to the offer, the claim
snapshots it onto the Order, and the pay-in intent / submission use only
that snapshot - never the mutable offer, never anything from the client.
"""
from decimal import Decimal
from urllib.parse import parse_qsl, urlsplit

import pytest
from rest_framework.test import APIClient

from orders.models import Currency, Order, OrderOffer
from payins.models import PayIn

from conftest import buy_method_for, next_key

OFFER_UPI = 'collect.one@ybl'


def _client(user):
    client = APIClient()
    client.force_authenticate(user)
    return client


@pytest.fixture
def member(make_user, make_method):
    user = make_user()
    make_method(user, 'member.payout@ybl', is_default=True)
    return user


@pytest.fixture
def upi_offer(db):
    return OrderOffer.objects.create(
        currency=Currency.INR,
        order_amount=Decimal('500'),
        income_rate=Decimal('10.00'),
        is_active=True,
        pay_in_upi_id=OFFER_UPI,
    )


def _claim(client, offer):
    response = client.post(
        '/api/orders/claim/',
        {'offer_id': offer.pk, 'idempotency_key': next_key()},
        format='json',
    )
    assert response.status_code == 201, response.data
    return response.data['data']['id']


def _intent(client, order_id):
    response = client.get(f'/api/orders/{order_id}/pay-in-intent/')
    assert response.status_code == 200, response.data
    return response.data['data']


def test_admin_created_offer_stores_pay_in_upi(admin_user):
    response = _client(admin_user).post(
        '/api/admin/offers/',
        {
            'order_amount': '500',
            'income_rate': '10.00',
            'quantity_available': 20,
            'is_active': True,
            'pay_in_upi_id': OFFER_UPI,
            'idempotency_key': next_key(),
        },
        format='json',
    )
    assert response.status_code == 201, response.data
    assert response.data['data']['pay_in_upi_id'] == OFFER_UPI
    assert OrderOffer.objects.get().pay_in_upi_id == OFFER_UPI


def test_member_offer_list_hides_pay_in_upi(member, upi_offer):
    response = _client(member).get('/api/orders/offers/')
    assert response.status_code == 200
    assert OFFER_UPI not in str(response.content)
    assert 'pay_in_upi_id' not in response.data['data']['offers'][0]


def test_claim_snapshots_upi_and_later_offer_edit_is_ignored(member, upi_offer):
    client = _client(member)
    order_id = _claim(client, upi_offer)
    assert Order.objects.get(pk=order_id).pay_in_upi_id == OFFER_UPI

    upi_offer.pay_in_upi_id = 'changed@ybl'
    upi_offer.save()

    intent = _intent(client, order_id)
    assert intent['payee_vpa'] == OFFER_UPI
    assert intent['amount'] == '500.00'
    query = dict(parse_qsl(urlsplit(intent['upi_uri']).query))
    assert query['pa'] == OFFER_UPI
    assert query['am'] == '500.00'
    assert query['pn'] == 'Hawk Wallet'
    reference = Order.objects.get(pk=order_id).reference
    assert query['cu'] == 'INR'
    assert query['tn'] == f'Hawk Wallet order {reference}'
    # Personal VPA, no merchant code: no tr (PhonePe rejects P2P with tr).
    assert 'tr' not in query
    assert 'mc' not in query


def test_submit_records_snapshot_and_ignores_client_destination(member, upi_offer, receiving_account):
    client = _client(member)
    order_id = _claim(client, upi_offer)
    token = _intent(client, order_id)['intent_token']

    response = client.post(
        f'/api/orders/{order_id}/pay-in/',
        {
            'utr': '123456789012',
            'idempotency_key': next_key(),
            'intent_token': token,
            'buy_method_id': buy_method_for(member).pk,
            # Unknown fields are never read.
            'payee_vpa': 'attacker@ybl',
            'receiving_account': receiving_account.pk,
        },
        format='json',
    )
    assert response.status_code == 201, response.data
    payin = PayIn.objects.get()
    assert payin.payee_vpa_snapshot == OFFER_UPI
    assert payin.receiving_account is None
    # The member's BUY account is recorded and echoed; the order's Pay-In
    # merchant destination is a separate thing.
    assert payin.buy_method == buy_method_for(member)
    assert response.data['data']['buy_method']['id'] == payin.buy_method_id
    assert response.data['data']['payee_vpa'] == OFFER_UPI


def test_submit_without_buy_method_rejected(member, upi_offer):
    client = _client(member)
    order_id = _claim(client, upi_offer)
    token = _intent(client, order_id)['intent_token']

    response = client.post(
        f'/api/orders/{order_id}/pay-in/',
        {'utr': '123456789012', 'idempotency_key': next_key(), 'intent_token': token},
        format='json',
    )
    assert response.status_code == 400
    assert response.data['errors']['code'] == 'buy_method_required'
    assert not PayIn.objects.exists()


def test_legacy_account_token_rejected_for_snapshot_order(member, upi_offer, receiving_account):
    from django.core import signing

    client = _client(member)
    order_id = _claim(client, upi_offer)
    forged = signing.dumps(
        {'u': member.pk, 'o': order_id, 'a': receiving_account.pk}, salt='payins.pay_in_intent'
    )
    response = client.post(
        f'/api/orders/{order_id}/pay-in/',
        {'utr': '123456789012', 'idempotency_key': next_key(), 'intent_token': forged},
        format='json',
    )
    assert response.status_code == 400
    assert not PayIn.objects.exists()


def test_admin_order_detail_shows_pay_in_upi_separately(member, admin_user, upi_offer):
    order_id = _claim(_client(member), upi_offer)

    data = _client(admin_user).get(f'/api/admin/orders/{order_id}/').data['data']
    assert data['pay_in_upi_id'] == OFFER_UPI
    assert data['payout_method']['upi_id'] == 'member.payout@ybl'
