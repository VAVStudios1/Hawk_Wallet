"""API tests for /api/profile/ (read, name change, password change)."""
import pytest
from django.core.cache import cache
from rest_framework.test import APIClient
from rest_framework_simplejwt.token_blacklist.models import BlacklistedToken
from rest_framework_simplejwt.tokens import RefreshToken

from audit.models import AuditEvent
from core.models import User
from core.tokens import issue_token_pair

PROFILE_URL = '/api/profile/'
PASSWORD_URL = '/api/profile/change-password/'
REFRESH_URL = '/api/auth/token/refresh/'

# conftest.make_user's password.
CURRENT_PASSWORD = 'unused-password-123'
NEW_PASSWORD = 'Brand-new-Pass-9081'


@pytest.fixture(autouse=True)
def _clear_throttles():
    # Throttle history lives in the cache and would leak between tests.
    cache.clear()


def _client(user):
    client = APIClient()
    client.force_authenticate(user)
    return client


def _change_password(user, **overrides):
    body = {
        'current_password': CURRENT_PASSWORD,
        'new_password': NEW_PASSWORD,
        'confirm_password': NEW_PASSWORD,
        **overrides,
    }
    return _client(user).post(PASSWORD_URL, body, format='json')


def _refresh(raw):
    return APIClient().post(REFRESH_URL, {'refresh': raw}, format='json')


def _events(user, action):
    return AuditEvent.objects.filter(
        action=action, target_type='core.User', target_id=str(user.pk)
    )


# --- access ------------------------------------------------------------------


@pytest.mark.parametrize('method, url', [
    ('get', PROFILE_URL),
    ('patch', PROFILE_URL),
    ('post', PASSWORD_URL),
])
def test_anonymous_is_rejected(db, method, url):
    response = getattr(APIClient(), method)(url, {}, format='json')
    assert response.status_code == 401
    assert response.data['success'] is False


@pytest.mark.parametrize('method, url', [
    ('get', PROFILE_URL),
    ('patch', PROFILE_URL),
    ('post', PASSWORD_URL),
])
def test_unverified_user_is_rejected(make_user, method, url):
    user = make_user(is_verified=False)
    response = getattr(_client(user), method)(url, {'name': 'X'}, format='json')
    assert response.status_code == 403


def test_inactive_user_is_rejected(make_user):
    user = make_user(is_active=False)
    assert _client(user).get(PROFILE_URL).status_code == 403


# --- GET ---------------------------------------------------------------------


def test_get_returns_own_profile_only(make_user):
    user = make_user()
    user.name = 'Lucky'
    user.save(update_fields=['name'])
    make_user()

    response = _client(user).get(PROFILE_URL)

    assert response.status_code == 200, response.data
    assert response.data['success'] is True
    data = response.data['data']
    assert data == {
        'id': user.pk,
        'name': 'Lucky',
        'mobile_number': user.mobile_number,
        'role': User.Role.MEMBER,
        'is_verified': True,
        'date_joined': data['date_joined'],
        'referral_code': user.referral_code,
    }


def test_get_exposes_no_financial_or_secret_fields(user):
    data = _client(user).get(PROFILE_URL).data['data']
    for key in ('password', 'balance', 'available', 'pending', 'total_earnings', 'referrer'):
        assert key not in data


def test_admin_can_read_own_profile(admin_user):
    response = _client(admin_user).get(PROFILE_URL)
    assert response.status_code == 200
    assert response.data['data']['role'] == User.Role.ADMIN


# --- PATCH (name) ------------------------------------------------------------


def test_patch_trims_and_saves_name_with_audit(user):
    old_name = user.name

    response = _client(user).patch(PROFILE_URL, {'name': '  New Name  '}, format='json')

    assert response.status_code == 200, response.data
    assert response.data['data']['name'] == 'New Name'
    user.refresh_from_db()
    assert user.name == 'New Name'
    event = _events(user, AuditEvent.Action.PROFILE_NAME_CHANGED).get()
    assert event.actor == user
    assert event.ip_address == '127.0.0.1'
    assert event.metadata == {'old_name': old_name, 'new_name': 'New Name'}


def test_name_can_change_more_than_once(user):
    client = _client(user)
    assert client.patch(PROFILE_URL, {'name': 'First'}, format='json').status_code == 200
    assert client.patch(PROFILE_URL, {'name': 'Second'}, format='json').status_code == 200

    user.refresh_from_db()
    assert user.name == 'Second'
    assert _events(user, AuditEvent.Action.PROFILE_NAME_CHANGED).count() == 2


def test_same_name_is_a_no_op(user):
    response = _client(user).patch(PROFILE_URL, {'name': user.name}, format='json')

    assert response.status_code == 200
    assert not _events(user, AuditEvent.Action.PROFILE_NAME_CHANGED).exists()


@pytest.mark.parametrize('body', [{}, {'name': ''}, {'name': '    '}, {'name': 'x' * 151}])
def test_patch_rejects_missing_blank_or_long_name(user, body):
    old_name = user.name

    response = _client(user).patch(PROFILE_URL, body, format='json')

    assert response.status_code == 400
    assert response.data['success'] is False
    assert 'name' in response.data['errors']
    user.refresh_from_db()
    assert user.name == old_name
    assert not _events(user, AuditEvent.Action.PROFILE_NAME_CHANGED).exists()


def test_patch_ignores_fixed_fields(user):
    before = User.objects.get(pk=user.pk)

    response = _client(user).patch(PROFILE_URL, {
        'name': 'Renamed',
        'mobile_number': '9999999999',
        'role': User.Role.SUPER_ADMIN,
        'referral_code': 'ABCDEFGH',
        'is_verified': False,
        'is_staff': True,
    }, format='json')

    assert response.status_code == 200, response.data
    user.refresh_from_db()
    assert user.name == 'Renamed'
    assert user.mobile_number == before.mobile_number
    assert user.role == User.Role.MEMBER
    assert user.referral_code == before.referral_code
    assert user.is_verified is True
    assert user.is_staff is False


# --- change password ---------------------------------------------------------


def test_change_password_success(user):
    response = _change_password(user)

    assert response.status_code == 200, response.data
    assert response.data['success'] is True
    assert set(response.data['data']) == {'access', 'refresh'}
    user.refresh_from_db()
    assert user.check_password(NEW_PASSWORD)
    assert not user.check_password(CURRENT_PASSWORD)


def test_change_password_revokes_all_other_refresh_tokens(user, make_user):
    phone = issue_token_pair(user)
    tablet = issue_token_pair(user)
    stranger = issue_token_pair(make_user())

    response = _change_password(user)

    assert response.status_code == 200, response.data
    assert _refresh(phone['refresh']).status_code == 401
    assert _refresh(tablet['refresh']).status_code == 401
    # Other users' sessions are untouched.
    assert _refresh(stranger['refresh']).status_code == 200


def test_change_password_returns_working_pair_for_this_device(user):
    issue_token_pair(user)

    new_pair = _change_password(user).data['data']

    jti = RefreshToken(new_pair['refresh'], verify=False)['jti']
    assert not BlacklistedToken.objects.filter(token__jti=jti).exists()
    assert _refresh(new_pair['refresh']).status_code == 200
    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION=f"Bearer {new_pair['access']}")
    assert client.get(PROFILE_URL).status_code == 200


def test_change_password_audit_holds_no_secrets(user):
    issue_token_pair(user)
    issue_token_pair(user)

    new_pair = _change_password(user).data['data']

    event = _events(user, AuditEvent.Action.PASSWORD_CHANGED).get()
    assert event.actor == user
    assert event.metadata == {'revoked_refresh_tokens': 2}
    stored = str(event.metadata) + event.target_id + event.target_type
    user.refresh_from_db()
    for secret in (CURRENT_PASSWORD, NEW_PASSWORD, user.password,
                   new_pair['access'], new_pair['refresh']):
        assert secret not in stored


def test_wrong_current_password_changes_nothing(user):
    tokens = issue_token_pair(user)

    response = _change_password(user, current_password='not-my-password')

    assert response.status_code == 400
    assert response.data['errors']['code'] == 'incorrect_current_password'
    assert 'current_password' in response.data['errors']
    user.refresh_from_db()
    assert user.check_password(CURRENT_PASSWORD)
    assert _refresh(tokens['refresh']).status_code == 200
    assert not _events(user, AuditEvent.Action.PASSWORD_CHANGED).exists()


def test_confirm_mismatch_is_rejected(user):
    response = _change_password(user, confirm_password='Something-else-7731')

    assert response.status_code == 400
    assert 'confirm_password' in response.data['errors']
    user.refresh_from_db()
    assert user.check_password(CURRENT_PASSWORD)


@pytest.mark.parametrize('weak', ['short', '12345678', 'password'])
def test_django_password_rules_apply(user, weak):
    response = _change_password(user, new_password=weak, confirm_password=weak)

    assert response.status_code == 400
    assert response.data['errors']['code'] == 'invalid_new_password'
    assert response.data['errors']['new_password']
    user.refresh_from_db()
    assert user.check_password(CURRENT_PASSWORD)
    assert not _events(user, AuditEvent.Action.PASSWORD_CHANGED).exists()


@pytest.mark.parametrize('missing', ['current_password', 'new_password', 'confirm_password'])
def test_all_password_fields_are_required(user, missing):
    body = {
        'current_password': CURRENT_PASSWORD,
        'new_password': NEW_PASSWORD,
        'confirm_password': NEW_PASSWORD,
    }
    del body[missing]

    response = _client(user).post(PASSWORD_URL, body, format='json')

    assert response.status_code == 400
    assert missing in response.data['errors']


def test_retry_with_old_password_after_success_fails(user):
    assert _change_password(user).status_code == 200

    retry = _change_password(user)

    assert retry.status_code == 400
    assert retry.data['errors']['code'] == 'incorrect_current_password'
    assert _events(user, AuditEvent.Action.PASSWORD_CHANGED).count() == 1


def test_change_password_is_throttled(user):
    for _ in range(5):
        _change_password(user, current_password='wrong-guess')

    response = _change_password(user)

    assert response.status_code == 429
    assert response.data['success'] is False
    user.refresh_from_db()
    assert user.check_password(CURRENT_PASSWORD)
