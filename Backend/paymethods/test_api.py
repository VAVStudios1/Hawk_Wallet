"""Smoke tests for the payout-method endpoints; the services own the rules."""
from io import BytesIO

import pytest
from django.core.files.uploadedfile import SimpleUploadedFile
from PIL import Image
from rest_framework.test import APIClient

from core.models import User
from paymethods.models import UserPayoutMethod
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
