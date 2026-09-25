"""Smoke tests for claim -> pay-in intent -> submit UTR; services own the rules."""
from decimal import Decimal

import pytest
from rest_framework.test import APIClient

from conftest import next_key
from core.models import User
from orders.models import Order
from payins.models import PayIn


@pytest.fixture
def member(make_user, make_method):
    user = make_user()
    make_method(user, 'member@ybl', is_default=True)
    return user


def _client(user):
    client = APIClient()
    client.force_authenticate(user)
    return client


def _claim(client, offer, **extra):
    return client.post(
        '/api/orders/claim/',
        {'offer_id': offer.pk, 'idempotency_key': next_key(), **extra},
        format='json',
    )


def test_claim_intent_submit_flow(member, offer, receiving_account):
    client = _client(member)

    # Client-sent financial fields and user are ignored.
    response = _claim(client, offer, user=999, order_amount='1', status='COMPLETED')
    assert response.status_code == 201, response.data
    order = response.data['data']
    assert order['status'] == Order.Status.PAYMENT_REQUIRED
    assert order['order_amount'] == '255.00' and order['payout_target'] == '265.00'
    assert order['payout_method']['upi_id'] == 'member@ybl'
    assert 'idempotency_key' not in order and 'user' not in order
    assert Order.objects.get(pk=order['id']).user == member

    response = client.get(f"/api/orders/{order['id']}/pay-in-intent/")
    assert response.status_code == 200, response.data
    intent = response.data['data']
    assert intent['order_reference'] == order['reference']
    assert intent['amount'] == '255.00'
    assert intent['payee_vpa'] == 'platform@ybl'
    assert 'receiving_account_id' not in intent
    assert not PayIn.objects.exists()

    response = client.post(
        f"/api/orders/{order['id']}/pay-in/",
        {
            'utr': '012345678901', 'idempotency_key': next_key(),
            'intent_token': intent['intent_token'], 'amount': '1', 'status': 'CONFIRMED',
        },
        format='json',
    )
    assert response.status_code == 201, response.data
    payin = response.data['data']
    assert payin['status'] == PayIn.Status.UNDER_REVIEW
    assert payin['amount'] == '255.00'
    assert payin['utr'] == '012345678901'
    assert PayIn.objects.get().amount == Decimal('255')
    assert Order.objects.get(pk=order['id']).status == Order.Status.PAYMENT_UNDER_REVIEW


@pytest.mark.parametrize('role', [User.Role.ADMIN, User.Role.SUPER_ADMIN])
def test_admin_cannot_claim(make_user, offer, role):
    response = _claim(_client(make_user(role=role)), offer)
    assert response.status_code == 403
    assert response.data['success'] is False
    assert not Order.objects.exists()


def test_anonymous_cannot_claim(offer):
    assert _claim(APIClient(), offer).status_code == 401


def test_other_members_order_looks_missing(member, make_user, make_method, offer, receiving_account):
    order = _claim(_client(member), offer).data['data']
    intruder = make_user()
    make_method(intruder, 'intruder@ybl', is_default=True)
    client = _client(intruder)

    theirs = client.get(f"/api/orders/{order['id']}/pay-in-intent/")
    missing = client.get('/api/orders/999999/pay-in-intent/')
    assert theirs.status_code == missing.status_code == 404
    assert theirs.data == missing.data


def test_submit_invalid_utr(member, offer, receiving_account):
    client = _client(member)
    order = _claim(client, offer).data['data']
    token = client.get(f"/api/orders/{order['id']}/pay-in-intent/").data['data']['intent_token']

    response = client.post(
        f"/api/orders/{order['id']}/pay-in/",
        {'utr': '12345678901\n', 'idempotency_key': next_key(), 'intent_token': token},
        format='json',
    )
    assert response.status_code == 400
    assert response.data['errors']['code'] == 'invalid_utr'
    assert not PayIn.objects.exists()
