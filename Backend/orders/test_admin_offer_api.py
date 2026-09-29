"""API tests for admin offer creation: access, validation, idempotency, audit."""
from decimal import Decimal

import pytest
from django.db import IntegrityError, transaction
from rest_framework.test import APIClient

from audit.models import AuditEvent, LedgerEntry
from conftest import next_key
from core.models import User
from orders.models import OrderOffer

URL = '/api/admin/offers/'


def _client(user):
    client = APIClient()
    client.force_authenticate(user)
    return client


def _body(**extra):
    body = {
        'order_amount': '255',
        'income_rate': '4.00',
        'quantity_available': 10,
        'is_active': True,
        'pay_in_upi_id': 'collect@ybl',
        'idempotency_key': next_key(),
    }
    body.update(extra)
    return body


# --- access ------------------------------------------------------------------

def test_anonymous_rejected(db):
    assert APIClient().post(URL, _body(), format='json').status_code == 401
    assert OrderOffer.objects.count() == 0


def test_member_forbidden(make_user):
    response = _client(make_user()).post(URL, _body(), format='json')
    assert response.status_code == 403
    assert OrderOffer.objects.count() == 0


def test_inactive_admin_rejected(make_user):
    admin = make_user(role=User.Role.ADMIN, is_active=False)
    assert _client(admin).post(URL, _body(), format='json').status_code in (401, 403)
    assert OrderOffer.objects.count() == 0


def test_get_not_allowed(admin_user):
    assert _client(admin_user).get(URL).status_code == 405


# --- happy path ----------------------------------------------------------------

@pytest.mark.parametrize('role', [User.Role.ADMIN, User.Role.SUPER_ADMIN])
def test_admin_creates_offer_with_server_computed_preview(make_user, role):
    admin = make_user(role=role)

    response = _client(admin).post(URL, _body(), format='json')

    assert response.status_code == 201, response.data
    data = response.data['data']
    offer = OrderOffer.objects.get()
    assert data['id'] == offer.pk
    assert data['currency'] == 'INR'
    assert data['order_amount'] == '255.00'
    assert data['income_rate'] == '4.00'
    # 255 x 4% = 10.20 -> 10 (half-up whole rupees), computed by the server.
    assert data['income_amount'] == '10.00'
    assert data['payout_target'] == '265.00'
    assert data['quantity_available'] == 10
    assert data['is_active'] is True
    assert offer.created_by == admin


def test_client_cannot_set_currency_income_or_window(admin_user):
    body = _body(
        currency='USD', income_amount='999.00', payout_target='999.00',
        valid_from='2020-01-01T00:00:00Z', valid_to='2020-01-02T00:00:00Z',
    )
    response = _client(admin_user).post(URL, body, format='json')

    assert response.status_code == 201, response.data
    offer = OrderOffer.objects.get()
    assert offer.currency == 'INR'
    assert offer.valid_from is None and offer.valid_to is None
    assert response.data['data']['payout_target'] == '265.00'


def test_no_quantity_means_unlimited(admin_user):
    body = _body()
    del body['quantity_available']
    response = _client(admin_user).post(URL, body, format='json')
    assert response.status_code == 201, response.data
    assert OrderOffer.objects.get().quantity_available is None


def test_create_records_one_audit_event_and_no_ledger(admin_user):
    _client(admin_user).post(URL, _body(), format='json')

    event = AuditEvent.objects.get()
    offer = OrderOffer.objects.get()
    assert event.actor == admin_user
    assert event.action == AuditEvent.Action.OFFER_CHANGED
    assert event.target_type == 'orders.OrderOffer'
    assert event.target_id == str(offer.pk)
    assert event.metadata == {'change': 'created'}
    assert LedgerEntry.objects.count() == 0


def test_active_offer_is_claimable_and_inactive_is_not(admin_user, make_user, make_method):
    client = _client(admin_user)
    active = client.post(URL, _body(), format='json').data['data']
    client.post(URL, _body(is_active=False), format='json')
    member = make_user()
    make_method(member, 'member@ybl', is_default=True)

    offers = _client(member).get('/api/orders/offers/').data['data']['offers']

    assert [o['id'] for o in offers] == [active['id']]
    assert offers[0]['payout_target'] == active['payout_target']


# --- idempotency -------------------------------------------------------------

def test_retry_with_same_key_returns_same_offer_once(admin_user):
    client = _client(admin_user)
    body = _body()

    first = client.post(URL, body, format='json')
    second = client.post(URL, body, format='json')

    assert first.status_code == second.status_code == 201
    assert first.data['data']['id'] == second.data['data']['id']
    assert OrderOffer.objects.count() == 1
    assert AuditEvent.objects.count() == 1


def test_retry_with_equivalent_decimal_is_replay(admin_user):
    client = _client(admin_user)
    key = next_key()
    client.post(URL, _body(order_amount='255', idempotency_key=key), format='json')
    again = client.post(URL, _body(order_amount='255.00', idempotency_key=key), format='json')
    assert again.status_code == 201
    assert OrderOffer.objects.count() == 1


@pytest.mark.parametrize('change', [
    {'order_amount': '300'},
    {'income_rate': '5.00'},
    {'quantity_available': 11},
    {'quantity_available': None},
    {'is_active': False},
    {'pay_in_upi_id': 'another@ybl'},
])
def test_same_key_different_values_conflicts(admin_user, change):
    client = _client(admin_user)
    key = next_key()
    client.post(URL, _body(idempotency_key=key), format='json')

    response = client.post(URL, _body(idempotency_key=key, **change), format='json')

    assert response.status_code == 409
    assert response.data['errors']['code'] == 'idempotency_key_conflict'
    assert OrderOffer.objects.count() == 1


def test_same_key_by_another_admin_conflicts(admin_user, make_user):
    key = next_key()
    _client(admin_user).post(URL, _body(idempotency_key=key), format='json')

    other = make_user(role=User.Role.SUPER_ADMIN)
    response = _client(other).post(URL, _body(idempotency_key=key), format='json')

    assert response.status_code == 409
    assert response.data['errors']['code'] == 'idempotency_key_conflict'
    assert OrderOffer.objects.count() == 1


def test_database_rejects_duplicate_key(admin_user):
    values = dict(order_amount=Decimal('255'), income_rate=Decimal('4'), idempotency_key='dup')
    OrderOffer.objects.create(**values)
    with pytest.raises(IntegrityError), transaction.atomic():
        OrderOffer.objects.create(**values)


def test_offers_without_key_do_not_collide(db):
    OrderOffer.objects.create(order_amount=Decimal('255'), income_rate=Decimal('4'))
    OrderOffer.objects.create(order_amount=Decimal('255'), income_rate=Decimal('4'))
    assert OrderOffer.objects.count() == 2


# --- validation ----------------------------------------------------------------

@pytest.mark.parametrize('change, code', [
    ({'order_amount': '0'}, 'invalid_offer'),
    ({'order_amount': '-5'}, 'invalid_offer'),
    ({'order_amount': '255.50'}, 'invalid_offer'),
    ({'income_rate': '-1'}, 'invalid_offer'),
    ({'income_rate': '100.01'}, 'invalid_offer'),
    ({'quantity_available': 0}, 'invalid_offer'),
    ({'quantity_available': -1}, 'invalid_offer'),
    ({'pay_in_upi_id': ''}, 'invalid_offer'),
    ({'pay_in_upi_id': '   '}, 'invalid_offer'),
    ({'pay_in_upi_id': 'not-a-upi'}, 'invalid_offer'),
    ({'pay_in_upi_id': ' collect@ybl'}, 'invalid_offer'),
    ({'pay_in_upi_id': 'collect@ybl\n'}, 'invalid_offer'),
    ({'idempotency_key': ''}, 'invalid_idempotency_key'),
    ({'idempotency_key': '   '}, 'invalid_idempotency_key'),
    ({'idempotency_key': 'k' * 65}, 'invalid_idempotency_key'),
])
def test_invalid_values_rejected_by_service(admin_user, change, code):
    response = _client(admin_user).post(URL, _body(**change), format='json')

    assert response.status_code == 400
    assert response.data['errors']['code'] == code
    assert OrderOffer.objects.count() == 0
    assert AuditEvent.objects.count() == 0


@pytest.mark.parametrize('change', [
    {'order_amount': 'abc'},
    {'order_amount': '255.001'},
    {'income_rate': '4.001'},
    {'income_rate': '1000'},
    {'is_active': None},
])
def test_malformed_input_rejected_by_serializer(admin_user, change):
    response = _client(admin_user).post(URL, _body(**change), format='json')
    assert response.status_code == 400
    assert response.data['success'] is False
    assert OrderOffer.objects.count() == 0


@pytest.mark.parametrize('rate, income, target', [
    ('0', '0.00', '255.00'),
    ('100', '255.00', '510.00'),
    ('0.20', '1.00', '256.00'),  # 0.51 -> 1 (half-up)
    ('0.19', '0.00', '255.00'),  # 0.4845 -> 0
])
def test_rate_boundaries_and_rounding(admin_user, rate, income, target):
    response = _client(admin_user).post(URL, _body(income_rate=rate), format='json')
    assert response.status_code == 201, response.data
    assert response.data['data']['income_amount'] == income
    assert response.data['data']['payout_target'] == target
