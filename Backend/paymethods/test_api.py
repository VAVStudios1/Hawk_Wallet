"""Smoke tests for the payout-method endpoints; the services own the rules."""
import importlib
from io import BytesIO

import pytest
from django.apps import apps as django_apps
from django.core.files.uploadedfile import SimpleUploadedFile
from PIL import Image
from rest_framework.test import APIClient

from core.models import User
from paymethods.models import PaymentProvider, UserPayoutMethod
from paymethods.storage import private_storage

URL = '/api/payout-methods/'


@pytest.fixture(autouse=True)
def private_media_root(settings, tmp_path):
    settings.PRIVATE_MEDIA_ROOT = tmp_path
    for attr in ('base_location', 'location'):
        private_storage.__dict__.pop(attr, None)
    yield tmp_path
    for attr in ('base_location', 'location'):
        private_storage.__dict__.pop(attr, None)


def _qr():
    buffer = BytesIO()
    Image.new('RGB', (64, 64), 'white').save(buffer, format='PNG')
    return SimpleUploadedFile('qr.png', buffer.getvalue(), content_type='image/png')


def _client(user=None):
    client = APIClient()
    if user is not None:
        client.force_authenticate(user)
    return client


def _add(client, provider, upi_id, **extra):
    return client.post(
        URL,
        {'provider': provider.pk, 'upi_id': upi_id, 'qr_image': _qr(), **extra},
        format='multipart',
    )


def test_member_manages_payout_methods(user, provider):
    client = _client(user)

    first = _add(client, provider, 'first@ybl', label='Main', user=999, is_default='false',
                 is_active='false', qr_sha256='x' * 64)
    assert first.status_code == 201, first.data
    body = first.data['data']
    assert set(body) == {
        'id', 'provider', 'provider_code', 'provider_name', 'label', 'upi_id',
        'is_default', 'is_active', 'created_at', 'updated_at',
    }
    # Client-sent user/is_default/is_active/qr_sha256 are ignored.
    method = UserPayoutMethod.objects.get(pk=body['id'])
    assert method.user == user
    assert body['is_default'] is True and body['is_active'] is True
    assert method.qr_sha256 != 'x' * 64
    assert body['label'] == 'Main' and body['provider_code'] == 'PHONEPE'

    second = _add(client, provider, 'second@ybl').data['data']
    assert second['is_default'] is False

    response = client.post(f"{URL}{second['id']}/default/")
    assert response.status_code == 200, response.data
    assert response.data['data']['is_default'] is True

    response = client.post(f"{URL}{body['id']}/deactivate/")
    assert response.status_code == 200, response.data
    assert response.data['data']['is_active'] is False


def test_invalid_input_and_duplicate(user, provider):
    client = _client(user)
    response = client.post(URL, {'provider': provider.pk, 'upi_id': 'a@ybl'}, format='multipart')
    assert response.status_code == 400
    assert 'qr_image' in response.data['errors']

    assert _add(client, provider, 'not-a-upi').status_code == 400
    assert _add(client, provider, 'dup@ybl').status_code == 201
    response = _add(client, provider, 'dup@ybl')
    assert response.status_code == 409
    assert response.data['errors']['code'] == 'duplicate_upi_id'


def test_other_members_method_looks_missing(make_user, provider):
    owner, intruder = make_user(), make_user()
    method = _add(_client(owner), provider, 'owner@ybl').data['data']
    client = _client(intruder)

    for action in ('default', 'deactivate'):
        theirs = client.post(f"{URL}{method['id']}/{action}/")
        missing = client.post(f'{URL}999999/{action}/')
        assert theirs.status_code == missing.status_code == 404
        assert theirs.data == missing.data
    assert UserPayoutMethod.objects.get(pk=method['id']).is_active


@pytest.mark.parametrize('extra', [
    {'role': User.Role.ADMIN},
    {'role': User.Role.SUPER_ADMIN},
    {'is_verified': False},
    {'is_active': False},
])
def test_non_members_forbidden(make_user, provider, extra):
    response = _add(_client(make_user(**extra)), provider, 'x@ybl')
    assert response.status_code == 403
    assert response.data['success'] is False
    assert not UserPayoutMethod.objects.exists()


def test_anonymous_unauthorized(provider):
    response = _add(_client(), provider, 'x@ybl')
    assert response.status_code == 401
    assert response.data['success'] is False


# --- GET /api/payout-methods/ --------------------------------------------------

LIST_FIELDS = {'id', 'provider', 'label', 'upi_id', 'is_default', 'is_active', 'created_at', 'updated_at'}


def _list(user=None):
    return _client(user).get(URL)


def _methods(user):
    response = _list(user)
    assert response.status_code == 200, response.data
    assert response.data['success'] is True
    return response.data['data']['methods']


def test_list_returns_own_methods(user, provider):
    client = _client(user)
    added = _add(client, provider, 'lucky@ybl', label='My PhonePe').data['data']

    [method] = _methods(user)
    assert set(method) == LIST_FIELDS
    assert method['id'] == added['id']
    assert method['label'] == 'My PhonePe'
    assert method['upi_id'] == 'lucky@ybl'
    assert method['provider'] == {'id': provider.pk, 'code': 'PHONEPE', 'display_name': 'PhonePe'}


def test_list_never_includes_other_members_methods(make_user, provider):
    mine, theirs = make_user(), make_user()
    _add(_client(mine), provider, 'mine@ybl')
    _add(_client(theirs), provider, 'theirs@ybl')

    assert [m['upi_id'] for m in _methods(mine)] == ['mine@ybl']
    assert [m['upi_id'] for m in _methods(theirs)] == ['theirs@ybl']


def test_list_default_and_active_flags(user, provider):
    client = _client(user)
    first = _add(client, provider, 'first@ybl').data['data']
    second = _add(client, provider, 'second@ybl').data['data']
    client.post(f"{URL}{second['id']}/default/")
    client.post(f"{URL}{first['id']}/deactivate/")

    flags = {m['upi_id']: (m['is_default'], m['is_active']) for m in _methods(user)}
    assert flags == {'second@ybl': (True, True), 'first@ybl': (False, False)}


def test_list_hides_qr_and_owner(user, provider):
    _add(_client(user), provider, 'lucky@ybl')
    stored = UserPayoutMethod.objects.get()
    assert stored.qr_sha256 and stored.qr_image.name

    response = _list(user)
    raw = response.content.decode()
    for secret in (stored.qr_image.name, stored.qr_sha256, 'qr_image', 'qr_sha256', 'payout_qr'):
        assert secret not in raw
    assert 'user' not in response.data['data']['methods'][0]


def test_list_empty_for_new_member(user):
    response = _list(user)
    assert response.status_code == 200
    assert response.data['success'] is True
    assert response.data['data'] == {'methods': []}


@pytest.mark.parametrize('extra', [
    {'is_verified': False},
    {'is_active': False},
    {'role': User.Role.ADMIN},
    {'role': User.Role.SUPER_ADMIN},
])
def test_list_forbidden_for_non_members(make_user, extra):
    response = _list(make_user(**extra))
    assert response.status_code == 403
    assert response.data['success'] is False


def test_list_requires_authentication():
    response = _list()
    assert response.status_code == 401
    assert response.data['success'] is False


def test_list_does_not_modify_methods(user, provider):
    from audit.models import AuditEvent

    client = _client(user)
    _add(client, provider, 'first@ybl')
    _add(client, provider, 'second@ybl')
    before = list(UserPayoutMethod.objects.order_by('pk').values())
    events = AuditEvent.objects.count()

    _methods(user)
    _methods(user)

    assert list(UserPayoutMethod.objects.order_by('pk').values()) == before
    assert AuditEvent.objects.count() == events


def test_list_orders_default_first_then_newest(user, provider):
    client = _client(user)
    oldest = _add(client, provider, 'oldest@ybl').data['data']      # default (first added)
    middle = _add(client, provider, 'middle@ybl').data['data']
    newest = _add(client, provider, 'newest@ybl').data['data']
    client.post(f"{URL}{middle['id']}/default/")

    ids = [m['id'] for m in _methods(user)]
    assert ids == [middle['id'], newest['id'], oldest['id']]


# --- seeded providers and provider_code ----------------------------------------

# BUSINESS / SELL providers (paymethods.0004): the only ones offered for new
# payout methods.
SEEDED = {
    'BHARATPE': 'BharatPe',
    'PAYTM_BUSINESS': 'Paytm Business',
    'PHONEPE_BUSINESS': 'PhonePe Business',
}
# PERSONAL / BUY providers (seeded by 0002, moved to BUY by 0004).
PERSONAL = {
    'PHONEPE': 'PhonePe',
    'GOOGLE_PAY': 'Google Pay',
    'BHIM': 'BHIM',
    'MOBIKWIK': 'MobiKwik',
}


def _add_by_code(client, code, upi_id='lucky@ybl', **extra):
    return client.post(
        URL,
        {'provider_code': code, 'upi_id': upi_id, 'qr_image': _qr(), **extra},
        format='multipart',
    )


@pytest.mark.django_db
def test_migration_seeds_payout_providers():
    for code, name in SEEDED.items():
        provider = PaymentProvider.objects.get(code=code)
        assert provider.display_name == name
        assert provider.is_active and provider.enabled_for_payout_methods
        assert not provider.enabled_for_buy_methods
        assert not provider.enabled_for_pay_in
    orders = [PaymentProvider.objects.get(code=code).sort_order for code in SEEDED]
    assert orders == sorted(orders)


@pytest.mark.django_db
def test_migration_moves_seeded_personal_providers_to_buy():
    for code, name in PERSONAL.items():
        provider = PaymentProvider.objects.get(code=code)
        # Not renamed, still active; only the side changed.
        assert provider.display_name == name and provider.is_active
        assert provider.enabled_for_buy_methods
        assert not provider.enabled_for_payout_methods
    # PhonePe and PhonePe Business are different providers.
    assert (
        PaymentProvider.objects.get(code='PHONEPE').pk
        != PaymentProvider.objects.get(code='PHONEPE_BUSINESS').pk
    )


@pytest.mark.parametrize('code', list(PERSONAL))
def test_personal_provider_code_rejected_for_new_payout_method(user, code):
    _assert_provider_rejected(_add_by_code(_client(user), code))


def test_existing_payout_method_on_personal_provider_keeps_working(user, make_method):
    # A SELL method saved on PHONEPE before the split stays usable after
    # PHONEPE is switched to BUY: flags only gate NEW methods.
    method = make_method(user, 'legacy@ybl', is_default=True)
    second = make_method(user, 'second@ybl')
    PaymentProvider.objects.filter(code='PHONEPE').update(
        enabled_for_payout_methods=False, enabled_for_buy_methods=True
    )
    client = _client(user)

    assert [m['id'] for m in _methods(user)] == [method.pk, second.pk]
    assert client.post(f'{URL}{second.pk}/default/').status_code == 200
    assert client.post(f'{URL}{method.pk}/deactivate/').status_code == 200


@pytest.mark.django_db
def test_seeding_again_keeps_existing_rows_untouched():
    seed = importlib.import_module('paymethods.migrations.0002_seed_payment_providers')
    PaymentProvider.objects.filter(code='BHIM').update(display_name='BHIM UPI', is_active=False)
    before = PaymentProvider.objects.count()

    seed.seed_providers(django_apps, None)

    assert PaymentProvider.objects.count() == before
    bhim = PaymentProvider.objects.get(code='BHIM')
    assert bhim.display_name == 'BHIM UPI' and bhim.is_active is False


@pytest.mark.parametrize('code', list(SEEDED))
def test_add_by_provider_code(user, code):
    response = _add_by_code(_client(user), code)

    assert response.status_code == 201, response.data
    body = response.data['data']
    assert body['provider_code'] == code
    assert body['provider_name'] == SEEDED[code]
    method = UserPayoutMethod.objects.get(pk=body['id'])
    assert method.provider.code == code and method.user == user


def test_provider_code_is_normalised(user):
    response = _add_by_code(_client(user), '  phonepe_business ')
    assert response.status_code == 201, response.data
    assert response.data['data']['provider_code'] == 'PHONEPE_BUSINESS'


def _assert_provider_rejected(response, field='provider'):
    assert response.status_code == 400, response.data
    assert response.data['success'] is False
    assert response.data['errors']['code'] == 'invalid'
    assert field in response.data['errors']
    assert not UserPayoutMethod.objects.exists()


def test_unknown_provider_code_rejected(user):
    response = _add_by_code(_client(user), 'PAYPAL')
    _assert_provider_rejected(response)
    assert response.data['errors']['provider'] == ['Choose an available payout provider.']


def test_inactive_provider_code_rejected(user):
    PaymentProvider.objects.filter(code='BHARATPE').update(is_active=False)
    _assert_provider_rejected(_add_by_code(_client(user), 'BHARATPE'))


def test_provider_code_disabled_for_payout_methods_rejected(user):
    PaymentProvider.objects.filter(code='PAYTM_BUSINESS').update(enabled_for_payout_methods=False)
    _assert_provider_rejected(_add_by_code(_client(user), 'PAYTM_BUSINESS'))


def test_provider_and_matching_code_accepted(user):
    business = PaymentProvider.objects.get(code='PHONEPE_BUSINESS')
    response = _add_by_code(_client(user), 'PHONEPE_BUSINESS', provider=business.pk)
    assert response.status_code == 201, response.data
    assert response.data['data']['provider'] == business.pk


def test_provider_and_conflicting_code_rejected(user):
    business = PaymentProvider.objects.get(code='PHONEPE_BUSINESS')
    response = _add_by_code(_client(user), 'BHARATPE', provider=business.pk)
    _assert_provider_rejected(response, field='provider_code')
    assert response.data['errors']['provider_code'] == [
        'provider and provider_code refer to different providers.'
    ]


def test_provider_id_only_still_works(user, provider):
    response = _add(_client(user), provider, 'lucky@ybl')
    assert response.status_code == 201, response.data
    assert response.data['data']['provider'] == provider.pk
    assert response.data['data']['provider_code'] == 'PHONEPE'


def test_unavailable_provider_id_still_rejected(user):
    bharatpe = PaymentProvider.objects.get(code='BHARATPE')
    PaymentProvider.objects.filter(pk=bharatpe.pk).update(is_active=False)
    response = _client(user).post(
        URL, {'provider': bharatpe.pk, 'upi_id': 'lucky@ybl', 'qr_image': _qr()}, format='multipart'
    )
    _assert_provider_rejected(response)


def test_missing_provider_and_code_rejected(user):
    response = _client(user).post(URL, {'upi_id': 'lucky@ybl', 'qr_image': _qr()}, format='multipart')
    assert response.status_code == 400
    assert response.data['errors']['provider'] == ['This field is required.']
    assert not UserPayoutMethod.objects.exists()


def test_provider_code_keeps_existing_rules(user):
    client = _client(user)
    assert _add_by_code(client, 'PHONEPE_BUSINESS', 'dup@ybl').status_code == 201
    duplicate = _add_by_code(client, 'BHARATPE', 'DUP@ybl')
    assert duplicate.status_code == 409
    assert duplicate.data['errors']['code'] == 'duplicate_upi_id'
    assert _add_by_code(client, 'PAYTM_BUSINESS', 'not-a-upi').status_code == 400


# --- QR image formats and damaged uploads ----------------------------------------

def _pattern_qr(fmt, name):
    image = Image.new('RGB', (120, 120), 'white')
    for x in range(0, 120, 12):
        for y in range(0, 120, 12):
            if (x + y) % 24 == 0:
                image.paste('black', (x, y, x + 12, y + 12))
    buffer = BytesIO()
    image.save(buffer, format=fmt)
    return SimpleUploadedFile(name, buffer.getvalue())


def _add_qr(client, upload, upi_id='lucky@ybl'):
    return client.post(
        URL,
        {'provider_code': 'PHONEPE_BUSINESS', 'upi_id': upi_id, 'label': '', 'qr_image': upload},
        format='multipart',
    )


@pytest.mark.parametrize('fmt, name', [('JPEG', 'qr.jpg'), ('PNG', 'qr.png'), ('WEBP', 'qr.webp')])
def test_valid_qr_formats_accepted(user, fmt, name, private_media_root):
    response = _add_qr(_client(user), _pattern_qr(fmt, name))

    assert response.status_code == 201, response.data
    method = UserPayoutMethod.objects.get(pk=response.data['data']['id'])
    # Stored re-encoded as PNG whatever the upload format.
    assert method.qr_image.name.endswith('.png')
    assert (private_media_root / method.qr_image.name).exists()


@pytest.mark.parametrize('cut', [2, 4, 10])
def test_truncated_jpeg_returns_400_not_500(user, cut, private_media_root):
    jpeg = _pattern_qr('JPEG', 'qr.jpg').read()

    response = _add_qr(_client(user), SimpleUploadedFile('qr.jpg', jpeg[:-cut]))

    assert response.status_code == 400
    assert response.data['success'] is False
    assert response.data['errors']['code'] == 'invalid'
    assert response.data['errors']['qr_image'] == [
        'This image is incomplete or damaged. Please upload your QR code again.'
    ]
    assert not UserPayoutMethod.objects.exists()
    stored = private_media_root / 'payout_qr'
    assert not stored.exists() or not any(stored.iterdir())
