"""Update-check / APK download API and X-App-Version enforcement."""
import hashlib

import pytest
from rest_framework.test import APIClient
from rest_framework_simplejwt.tokens import RefreshToken

from appupdates.conftest import DOWNLOAD_BASE_URL, apk_bytes

CHECK_URL = '/api/app/update-check/'


def _check(version, **headers):
    return APIClient().get(CHECK_URL, {'version': version}, **headers)


def _authed(user):
    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION=f'Bearer {RefreshToken.for_user(user).access_token}')
    return client


@pytest.fixture
def published(make_release):
    make_release('1.0.5', publish=True)  # previous release, now history
    return make_release('1.0.7', '1.0.6', publish=True, notes='Bug fixes\nSecurity improvements')


# --- update-check ------------------------------------------------------------

@pytest.mark.parametrize('installed,supported,update_available,force_update', [
    ('1.0.7', True, False, False),    # current == latest
    ('1.0.6', True, True, False),     # current == minimum
    ('1.0.5', False, True, True),     # below minimum
    ('1.0.10', True, False, False),   # above latest (numeric, not string, compare)
    ('2.0.0', True, False, False),
    ('0.9.99', False, True, True),
])
def test_update_check(db, published, installed, supported, update_available, force_update):
    response = _check(installed)
    assert response.status_code == 200
    body = response.json()
    assert body['success'] is True
    assert body['data'] == {
        'current_version': installed,
        'latest_version': '1.0.7',
        'minimum_supported_version': '1.0.6',
        'supported': supported,
        'update_available': update_available,
        'force_update': force_update,
        'apk_url': f'{DOWNLOAD_BASE_URL}/api/app/releases/1.0.7/apk/',
        'apk_sha256': published.apk_sha256,
        'apk_size': published.apk_size,
        'release_notes': ['Bug fixes', 'Security improvements'],
    }


def test_current_between_minimum_and_latest(db, make_release):
    make_release('1.0.9', '1.0.5', publish=True)
    data = _check('1.0.7').json()['data']
    assert data['supported'] and data['update_available'] and not data['force_update']


def test_update_check_without_published_release(db, make_release):
    make_release('1.0.9', '1.0.9')  # uploaded but never published
    response = _check('1.0.5')
    assert response.status_code == 200
    data = response.json()['data']
    assert data['supported'] is True
    assert data['update_available'] is False
    assert data['force_update'] is False
    assert data['latest_version'] is None
    assert data['apk_url'] is None
    assert data['release_notes'] == []


def test_update_check_with_http_base_url_offers_nothing(db, published, settings):
    settings.APP_RELEASE_DOWNLOAD_BASE_URL = 'http://updates.hawkwallet.example'
    data = _check('1.0.5').json()['data']
    assert data['force_update'] is False and data['apk_url'] is None


@pytest.mark.parametrize('version', ['', 'abc', '1.0', '1.0.0-beta', '1.0.0.1'])
def test_update_check_rejects_malformed_version(db, published, version):
    response = _check(version)
    assert response.status_code == 400
    assert response.json()['errors']['code'] == 'invalid_version'


def test_update_check_ignores_a_stale_token(db, published):
    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION='Bearer not-a-real-token')
    assert client.get(CHECK_URL, {'version': '1.0.5'}).status_code == 200


def test_response_exposes_no_storage_path(db, published):
    text = _check('1.0.5').content.decode()
    assert published.apk_file.name not in text
    assert 'app_releases' not in text


# --- APK download ----------------------------------------------------------------

def test_download_serves_published_apk(db, make_release):
    data = apk_bytes(b'release build')
    make_release('1.0.7', publish=True, data=data)
    response = APIClient().get('/api/app/releases/1.0.7/apk/')
    assert response.status_code == 200
    assert response['Content-Type'] == 'application/vnd.android.package-archive'
    assert 'HawkWallet-1.0.7.apk' in response['Content-Disposition']
    body = b''.join(response.streaming_content)
    assert hashlib.sha256(body).hexdigest() == hashlib.sha256(data).hexdigest()


def test_download_refuses_unpublished_or_unknown_release(db, make_release):
    make_release('1.0.6')
    make_release('1.0.7', publish=True)
    for version in ('1.0.6', '9.9.9', 'abc'):
        response = APIClient().get(f'/api/app/releases/{version}/apk/')
        assert response.status_code == 404
        assert response.json()['errors']['code'] == 'release_not_found'
    # Never reaches the view: the URL pattern does not match.
    assert APIClient().get('/api/app/releases/..%2F..%2Fsecret/apk/').status_code == 404


# --- enforcement -------------------------------------------------------------------

def test_outdated_app_is_rejected_on_business_api(db, published, user):
    response = _authed(user).get('/api/home/', HTTP_X_APP_VERSION='1.0.5')
    assert response.status_code == 426
    body = response.json()
    assert body['success'] is False
    assert body['message'] == 'Please update the app to continue.'
    assert body['errors'] == {
        'code': 'app_update_required',
        'minimum_supported_version': '1.0.6',
        'latest_version': '1.0.7',
    }


@pytest.mark.parametrize('path', ['/api/admin/payouts/', '/api/orders/', '/api/profile/'])
def test_outdated_app_is_rejected_on_admin_and_transaction_apis(db, published, admin_user, path):
    assert _authed(admin_user).get(path, HTTP_X_APP_VERSION='1.0.5').status_code == 426


@pytest.mark.parametrize('header', ['1.0.6', '1.0.7', '1.0.10'])
def test_supported_app_passes(db, published, user, header):
    response = _authed(user).get('/api/profile/', HTTP_X_APP_VERSION=header)
    assert response.status_code == 200


def test_malformed_version_header_is_treated_as_outdated(db, published, user):
    response = _authed(user).get('/api/profile/', HTTP_X_APP_VERSION='banana')
    assert response.status_code == 426


def test_missing_header_is_allowed_by_default(db, published, user):
    assert _authed(user).get('/api/profile/').status_code == 200


def test_missing_header_rejected_when_required(db, published, user, settings):
    settings.APP_VERSION_HEADER_REQUIRED = True
    assert _authed(user).get('/api/profile/').status_code == 426


def test_nothing_enforced_without_published_release(db, make_release, user):
    make_release('1.0.9', '1.0.9')  # unpublished
    assert _authed(user).get('/api/profile/', HTTP_X_APP_VERSION='1.0.0').status_code == 200


def test_nothing_enforced_when_release_not_downloadable(db, published, user, settings):
    settings.APP_RELEASE_DOWNLOAD_BASE_URL = ''
    assert _authed(user).get('/api/profile/', HTTP_X_APP_VERSION='1.0.0').status_code == 200


@pytest.mark.parametrize('method,path', [
    ('get', CHECK_URL + '?version=1.0.5'),
    ('get', '/api/app/releases/1.0.7/apk/'),
    ('post', '/api/auth/login/'),
    ('post', '/api/auth/token/refresh/'),
    ('post', '/api/auth/password-reset/request/'),
    ('post', '/api/auth/password-reset/verify/'),
    ('post', '/api/auth/password-reset/confirm/'),
    ('post', '/api/auth/register/'),
])
def test_recovery_endpoints_stay_open_for_outdated_app(db, published, method, path):
    response = getattr(APIClient(), method)(path, {}, HTTP_X_APP_VERSION='1.0.5')
    assert response.status_code != 426


def test_django_admin_is_not_enforced(db, published, client):
    assert client.get('/admin/login/', HTTP_X_APP_VERSION='1.0.5').status_code == 200
