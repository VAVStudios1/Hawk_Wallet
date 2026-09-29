"""
PERSONAL / BUY methods: the member's own UPI accounts they pay for orders
from. Services, API, DB constraints and the BUY / SELL provider split.
"""
import importlib
import threading

import pytest
from django.apps import apps as django_apps
from django.contrib.auth.models import AnonymousUser
from django.db import IntegrityError, connection, transaction
from rest_framework.test import APIClient

from audit.mixins import ImmutableFieldError
from audit.models import AuditEvent
from conftest import buy_method_for
from paymethods.buy_services import (
    add_buy_method,
    deactivate_buy_method,
    list_buy_methods,
    resolve_buy_provider_id,
    set_default_buy_method,
)
from paymethods.exceptions import (
    BuyMethodInactive,
    BuyMethodNotFound,
    BuyMethodPermissionDenied,
    DuplicateBuyMethod,
    InvalidBuyMethod,
)
from paymethods.models import PaymentProvider, UserBuyMethod, UserPayoutMethod
from payins.models import PayIn

URL = '/api/buy-methods/'
PERSONAL_CODES = ('PHONEPE', 'GOOGLE_PAY', 'BHIM', 'MOBIKWIK')
BUSINESS_CODES = ('BHARATPE', 'PAYTM_BUSINESS', 'PHONEPE_BUSINESS')


def _client(user=None):
    client = APIClient()
    if user is not None:
        client.force_authenticate(user)
    return client


def _add(user, upi_id='buyer@okaxis', code='GOOGLE_PAY', **kwargs):
    provider = PaymentProvider.objects.get(code=code)
    return add_buy_method(user=user, provider_id=provider.pk, upi_id=upi_id, **kwargs)


def _defaults(user):
    return list(UserBuyMethod.objects.filter(user=user, is_default=True))


def _events(method, action=None):
    events = AuditEvent.objects.filter(
        target_type='paymethods.UserBuyMethod', target_id=str(method.pk)
    )
    return events.filter(action=action) if action else events


# --- provider split -------------------------------------------------------------

@pytest.mark.django_db
def test_provider_flags_split_buy_and_sell():
    for code in PERSONAL_CODES:
        provider = PaymentProvider.objects.get(code=code)
        assert provider.enabled_for_buy_methods and not provider.enabled_for_payout_methods
    for code in BUSINESS_CODES:
        provider = PaymentProvider.objects.get(code=code)
        assert provider.enabled_for_payout_methods and not provider.enabled_for_buy_methods
        assert not provider.enabled_for_pay_in


@pytest.mark.django_db
def test_provider_cannot_be_enabled_for_both_buy_and_sell():
    with pytest.raises(IntegrityError), transaction.atomic():
        PaymentProvider.objects.filter(code='PHONEPE').update(enabled_for_payout_methods=True)
    with pytest.raises(IntegrityError), transaction.atomic():
        PaymentProvider.objects.create(
            code='BOTH', display_name='Both',
            enabled_for_buy_methods=True, enabled_for_payout_methods=True,
        )


@pytest.mark.django_db
def test_split_migration_is_repeatable_and_never_renames():
    migration = importlib.import_module('paymethods.migrations.0004_split_buy_sell_providers')
    PaymentProvider.objects.filter(code='BHARATPE').update(display_name='BharatPe Merchant')
    before = PaymentProvider.objects.count()

    migration.split_providers(django_apps, None)

    assert PaymentProvider.objects.count() == before
    assert PaymentProvider.objects.get(code='BHARATPE').display_name == 'BharatPe Merchant'
    assert PaymentProvider.objects.get(code='PHONEPE').display_name == 'PhonePe'


@pytest.mark.parametrize('code', BUSINESS_CODES)
def test_business_provider_rejected_for_buy_method(user, code):
    with pytest.raises(InvalidBuyMethod) as exc:
        _add(user, code=code)
    assert 'provider' in exc.value.errors
    with pytest.raises(InvalidBuyMethod):
        resolve_buy_provider_id(provider_code=code)
    assert not UserBuyMethod.objects.exists()


@pytest.mark.parametrize('code', PERSONAL_CODES)
def test_personal_provider_accepted_for_buy_method(user, code):
    method = _add(user, code=code)
    assert method.provider.code == code


def test_inactive_personal_provider_rejected(user):
    PaymentProvider.objects.filter(code='BHIM').update(is_active=False)
    with pytest.raises(InvalidBuyMethod):
        _add(user, code='BHIM')


# --- add / default / deactivate -------------------------------------------------

def test_add_first_method_becomes_default_and_is_audited(user):
    method = _add(user, 'first@okaxis', label='Personal', ip_address='10.0.0.1')

    method.refresh_from_db()
    assert method.user == user and method.is_active and method.is_default
    assert method.label == 'Personal'
    event = _events(method, AuditEvent.Action.BUY_METHOD_CREATED).get()
    assert event.actor == user and event.ip_address == '10.0.0.1'
    assert event.metadata == {'provider': 'GOOGLE_PAY', 'is_default': True}
    assert 'first@okaxis' not in str(event.metadata)


def test_second_method_is_not_default_unless_asked(user):
    first = _add(user, 'first@okaxis')
    second = _add(user, 'second@okaxis')
    assert _defaults(user) == [first]

    third = _add(user, 'third@okaxis', make_default=True)
    assert _defaults(user) == [third]
    second.refresh_from_db()
    assert not second.is_default


def test_duplicate_active_upi_rejected_case_insensitively(user):
    _add(user, 'dup@okaxis')
    with pytest.raises(DuplicateBuyMethod):
        _add(user, 'DUP@okaxis', code='PHONEPE')
    assert UserBuyMethod.objects.count() == 1


def test_same_upi_allowed_after_deactivation(user):
    first = _add(user, 'reuse@okaxis')
    deactivate_buy_method(user=user, method_id=first.pk)
    assert _add(user, 'reuse@okaxis').is_active


def test_same_upi_may_exist_as_buy_and_sell(user, make_method):
    make_method(user, 'shared@ybl')
    method = _add(user, 'shared@ybl')
    assert method.upi_id == 'shared@ybl'
    assert UserPayoutMethod.objects.filter(user=user, upi_id='shared@ybl').exists()


def test_different_users_may_share_upi(make_user):
    _add(make_user(), 'family@okaxis')
    _add(make_user(), 'family@okaxis')
    assert UserBuyMethod.objects.count() == 2


@pytest.mark.parametrize('upi_id', ['', 'no-at-sign', 'a@', '@bank'])
def test_invalid_upi_rejected(user, upi_id):
    with pytest.raises(InvalidBuyMethod) as exc:
        _add(user, upi_id)
    assert 'upi_id' in exc.value.errors


def test_set_default_moves_default_and_audits(user):
    first = _add(user, 'first@okaxis')
    second = _add(user, 'second@okaxis')

    set_default_buy_method(user=user, method_id=second.pk)

    assert _defaults(user) == [second]
    event = _events(second, AuditEvent.Action.BUY_METHOD_DEFAULT_CHANGED).get()
    assert event.metadata == {'previous_default_id': first.pk}


def test_set_default_on_inactive_rejected(user):
    _add(user, 'first@okaxis')
    second = _add(user, 'second@okaxis')
    deactivate_buy_method(user=user, method_id=second.pk)
    with pytest.raises(BuyMethodInactive):
        set_default_buy_method(user=user, method_id=second.pk)


def test_deactivate_default_promotes_newest_remaining(user):
    first = _add(user, 'first@okaxis')
    _add(user, 'second@okaxis')
    third = _add(user, 'third@okaxis')

    deactivate_buy_method(user=user, method_id=first.pk)

    first.refresh_from_db()
    assert not first.is_active and not first.is_default
    assert _defaults(user) == [third]
    event = _events(first, AuditEvent.Action.BUY_METHOD_DEACTIVATED).get()
    assert event.metadata == {'was_default': True, 'new_default_id': third.pk}


def test_deactivate_is_idempotent(user):
    method = _add(user)
    deactivate_buy_method(user=user, method_id=method.pk)
    deactivate_buy_method(user=user, method_id=method.pk)
    assert _events(method, AuditEvent.Action.BUY_METHOD_DEACTIVATED).count() == 1


def test_other_users_method_is_not_found(make_user):
    owner, other = make_user(), make_user()
    method = _add(owner)
    with pytest.raises(BuyMethodNotFound):
        set_default_buy_method(user=other, method_id=method.pk)
    with pytest.raises(BuyMethodNotFound):
        deactivate_buy_method(user=other, method_id=method.pk)
    method.refresh_from_db()
    assert method.is_active and method.is_default


def test_payout_method_id_is_not_a_buy_method(user, make_method):
    payout = make_method(user, 'sell@ybl')
    assert not UserBuyMethod.objects.filter(pk=payout.pk).exists()
    with pytest.raises(BuyMethodNotFound):
        deactivate_buy_method(user=user, method_id=payout.pk)
    payout.refresh_from_db()
    assert payout.is_active


@pytest.mark.parametrize('who', ['anonymous', 'unverified', 'admin'])
def test_non_members_denied(make_user, admin_user, who):
    actor = {
        'anonymous': AnonymousUser(),
        'unverified': make_user(is_verified=False),
        'admin': admin_user,
    }[who]
    with pytest.raises(BuyMethodPermissionDenied):
        _add(actor)
    with pytest.raises(BuyMethodPermissionDenied):
        list_buy_methods(user=actor)


def test_list_is_own_methods_default_first(make_user):
    user, other = make_user(), make_user()
    first = _add(user, 'first@okaxis')
    second = _add(user, 'second@okaxis')
    _add(other, 'other@okaxis')
    set_default_buy_method(user=user, method_id=second.pk)

    assert list(list_buy_methods(user=user)) == [second, first]


# --- DB constraints and immutability ------------------------------------------------

def test_db_allows_one_default_per_user(user, make_buy_method):
    make_buy_method(user, 'a@okaxis', is_default=True)
    with pytest.raises(IntegrityError), transaction.atomic():
        make_buy_method(user, 'b@okaxis', is_default=True)


def test_db_rejects_inactive_default(user, make_buy_method):
    with pytest.raises(IntegrityError), transaction.atomic():
        make_buy_method(user, is_default=True, is_active=False)


def test_db_rejects_duplicate_active_upi(user, make_buy_method):
    make_buy_method(user, 'same@okaxis')
    with pytest.raises(IntegrityError), transaction.atomic():
        make_buy_method(user, 'SAME@okaxis')


def test_destination_frozen_once_used_by_payin(user, make_buy_method, make_order):
    method = make_buy_method(user, 'paid.from@okaxis', is_default=True)
    order = make_order(user)
    PayIn.objects.create(
        order=order, payee_vpa_snapshot='platform@ybl', utr='123456789012',
        idempotency_key='k', buy_method=method,
    )

    method.upi_id = 'changed@okaxis'
    with pytest.raises(ImmutableFieldError):
        method.save()

    # Flags may still change: deactivating never touches the PayIn.
    deactivate_buy_method(user=user, method_id=method.pk)
    assert PayIn.objects.get().buy_method_id == method.pk


def test_unused_method_may_still_be_edited_by_model(user, make_buy_method):
    method = make_buy_method(user, 'fresh@okaxis')
    method.upi_id = 'fresher@okaxis'
    method.save()


# --- API ------------------------------------------------------------------------

def test_api_member_manages_buy_methods(user):
    client = _client(user)

    first = client.post(
        URL,
        {'provider_code': 'phonepe', 'upi_id': 'me@ybl', 'label': 'Personal',
         'user': 999, 'is_default': False, 'is_active': False},
        format='json',
    )
    assert first.status_code == 201, first.data
    body = first.data['data']
    assert set(body) == {
        'id', 'provider', 'provider_code', 'provider_name', 'label', 'upi_id',
        'is_default', 'is_active', 'created_at', 'updated_at',
    }
    # Client-sent user/is_default/is_active are ignored.
    assert body['provider_code'] == 'PHONEPE' and body['provider_name'] == 'PhonePe'
    assert body['is_default'] and body['is_active']
    assert UserBuyMethod.objects.get(pk=body['id']).user == user

    second = client.post(URL, {'provider_code': 'BHIM', 'upi_id': 'me@upi'}, format='json')
    assert second.status_code == 201, second.data
    second_id = second.data['data']['id']

    listed = client.get(URL)
    assert listed.status_code == 200
    methods = listed.data['data']['methods']
    assert [m['id'] for m in methods] == [body['id'], second_id]
    assert methods[0]['provider'] == {
        'id': body['provider'], 'code': 'PHONEPE', 'display_name': 'PhonePe',
    }

    assert client.post(f'{URL}{second_id}/default/').data['data']['is_default'] is True
    deactivated = client.post(f'{URL}{second_id}/deactivate/')
    assert deactivated.status_code == 200
    assert deactivated.data['data']['is_active'] is False


def test_api_business_provider_code_rejected(user):
    response = _client(user).post(
        URL, {'provider_code': 'PHONEPE_BUSINESS', 'upi_id': 'me@ybl'}, format='json'
    )
    assert response.status_code == 400
    assert response.data['errors']['code'] == 'invalid'
    assert not UserBuyMethod.objects.exists()


def test_api_errors(user, make_user):
    client = _client(user)
    assert client.post(URL, {'upi_id': 'me@ybl'}, format='json').status_code == 400
    client.post(URL, {'provider_code': 'GOOGLE_PAY', 'upi_id': 'me@ybl'}, format='json')
    duplicate = client.post(URL, {'provider_code': 'BHIM', 'upi_id': 'ME@ybl'}, format='json')
    assert duplicate.status_code == 409
    assert duplicate.data['errors']['code'] == 'duplicate_upi_id'

    others = buy_method_for(make_user())
    assert client.post(f'{URL}{others.pk}/default/').status_code == 404


def test_api_requires_verified_member(make_user, admin_user):
    assert _client().get(URL).status_code == 401
    assert _client(make_user(is_verified=False)).get(URL).status_code == 403
    assert _client(admin_user).post(
        URL, {'provider_code': 'GOOGLE_PAY', 'upi_id': 'a@ybl'}, format='json'
    ).status_code == 403


def test_payout_methods_api_lists_no_buy_methods(user):
    buy_method_for(user)
    response = _client(user).get('/api/payout-methods/')
    assert response.status_code == 200
    assert response.data['data']['methods'] == []


# --- concurrency (PostgreSQL only) ------------------------------------------------

postgres_only = pytest.mark.skipif(
    connection.vendor != 'postgresql',
    reason='Row locks (select_for_update) are a no-op on SQLite.',
)


@postgres_only
@pytest.mark.django_db(transaction=True)
def test_concurrent_adds_of_same_upi_leave_one_active(make_user):
    user = make_user()
    barrier = threading.Barrier(3)
    results = []

    def worker():
        try:
            barrier.wait()
            results.append(_add(user, 'race@okaxis'))
        except Exception as exc:
            results.append(exc)
        finally:
            connection.close()

    threads = [threading.Thread(target=worker) for _ in range(3)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()

    assert UserBuyMethod.objects.filter(user=user, is_active=True).count() == 1
    assert len(_defaults(user)) == 1
    assert sum(isinstance(r, UserBuyMethod) for r in results) == 1
