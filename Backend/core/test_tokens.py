"""API tests for POST /api/auth/token/refresh/ and POST /api/auth/logout/."""
from datetime import timedelta

import pytest
from django.conf import settings
from django.core.cache import cache
from django.utils import timezone
from rest_framework.test import APIClient
from rest_framework_simplejwt.token_blacklist.models import BlacklistedToken, OutstandingToken
from rest_framework_simplejwt.tokens import RefreshToken
from rest_framework_simplejwt.utils import datetime_to_epoch

from core.tokens import LAST_ACTIVITY_CLAIM, issue_token_pair

REFRESH_URL = '/api/auth/token/refresh/'
LOGOUT_URL = '/api/auth/logout/'


@pytest.fixture(autouse=True)
def _clear_throttles():
    # Throttle history lives in the cache and would leak between tests.
    cache.clear()


def _refresh(raw, client=None, **extra):
    return (client or APIClient()).post(REFRESH_URL, {'refresh': raw, **extra}, format='json')


def _logout(access, raw):
    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION=f'Bearer {access}')
    return client.post(LOGOUT_URL, {'refresh': raw}, format='json')


def _is_blacklisted(raw):
    jti = RefreshToken(raw, verify=False)['jti']
    return BlacklistedToken.objects.filter(token__jti=jti).exists()


# --- refresh -----------------------------------------------------------------


def test_login_style_token_is_tracked(user):
    tokens = issue_token_pair(user)
    jti = RefreshToken(tokens['refresh'], verify=False)['jti']
    assert OutstandingToken.objects.filter(jti=jti, user=user).exists()


def test_refresh_returns_new_pair_in_envelope(user):
    tokens = issue_token_pair(user)

    response = _refresh(tokens['refresh'])

    assert response.status_code == 200, response.data
    assert response.data['success'] is True
    data = response.data['data']
    assert set(data) == {'access', 'refresh'}
    assert data['refresh'] != tokens['refresh']
    # The new refresh token is tracked, so it can later be revoked.
    new_jti = RefreshToken(data['refresh'], verify=False)['jti']
    assert OutstandingToken.objects.filter(jti=new_jti, user=user).exists()


def test_new_access_token_authenticates(user):
    data = _refresh(issue_token_pair(user)['refresh']).data['data']
    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION=f"Bearer {data['access']}")
    assert client.get('/api/profile/').status_code == 200


def test_rotated_refresh_token_cannot_be_reused(user):
    old = issue_token_pair(user)['refresh']
    assert _refresh(old).status_code == 200

    retry = _refresh(old)

    assert retry.status_code == 401
    assert retry.data['success'] is False
    assert retry.data['errors']['code'] == 'token_not_valid'
    assert _is_blacklisted(old)


def test_rotated_chain_keeps_working(user):
    raw = issue_token_pair(user)['refresh']
    for _ in range(3):
        response = _refresh(raw)
        assert response.status_code == 200, response.data
        raw = response.data['data']['refresh']


def test_refresh_rejects_access_token(user):
    response = _refresh(issue_token_pair(user)['access'])
    assert response.status_code == 401
    assert response.data['errors']['code'] == 'token_not_valid'


@pytest.mark.parametrize('raw', ['garbage', 'a.b.c'])
def test_refresh_rejects_malformed_token(db, raw):
    response = _refresh(raw)
    assert response.status_code == 401
    assert response.data['success'] is False


def test_refresh_requires_token_field(db):
    response = APIClient().post(REFRESH_URL, {}, format='json')
    assert response.status_code == 400
    assert 'refresh' in response.data['errors']


def test_refresh_rejects_untracked_token(user):
    # e.g. a token signed before the blacklist app was installed: it could
    # never be revoked, so it is not accepted.
    raw = issue_token_pair(user)['refresh']
    OutstandingToken.objects.filter(jti=RefreshToken(raw, verify=False)['jti']).delete()

    assert _refresh(raw).status_code == 401


def test_refresh_rejects_inactive_user(user):
    raw = issue_token_pair(user)['refresh']
    user.is_active = False
    user.save(update_fields=['is_active'])

    assert _refresh(raw).status_code == 401


def test_refresh_ignores_bad_authorization_header(user):
    # An expired/invalid access token in the header must not block refresh.
    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION='Bearer not-a-real-token')

    response = _refresh(issue_token_pair(user)['refresh'], client=client)

    assert response.status_code == 200, response.data


# --- logout ------------------------------------------------------------------


def test_logout_blacklists_own_refresh_token(user):
    tokens = issue_token_pair(user)

    response = _logout(tokens['access'], tokens['refresh'])

    assert response.status_code == 200, response.data
    assert response.data == {'success': True, 'message': 'Logged out.', 'data': {}}
    assert _is_blacklisted(tokens['refresh'])
    assert _refresh(tokens['refresh']).status_code == 401


def test_logout_only_revokes_the_submitted_token(user):
    phone = issue_token_pair(user)
    tablet = issue_token_pair(user)

    assert _logout(phone['access'], phone['refresh']).status_code == 200

    assert _refresh(tablet['refresh']).status_code == 200


def test_logout_works_without_an_access_token(user):
    # The refresh token is the credential, so a client whose access token
    # has already expired can still end its session.
    raw = issue_token_pair(user)['refresh']

    response = APIClient().post(LOGOUT_URL, {'refresh': raw}, format='json')

    assert response.status_code == 200, response.data
    assert _is_blacklisted(raw)
    assert _refresh(raw).status_code == 401


def test_logout_ignores_an_expired_access_token_header(user):
    raw = issue_token_pair(user)['refresh']

    response = _logout('expired-or-garbage-access-token', raw)

    assert response.status_code == 200, response.data
    assert _is_blacklisted(raw)


def test_logout_rejects_invalid_token(user):
    access = issue_token_pair(user)['access']

    response = _logout(access, 'garbage')

    assert response.status_code == 400
    assert response.data['errors']['code'] == 'token_not_valid'


def test_logout_rejects_access_token_as_refresh(user):
    access = issue_token_pair(user)['access']
    assert _logout(access, access).status_code == 400


def test_logout_twice_is_rejected_second_time(user):
    tokens = issue_token_pair(user)
    assert _logout(tokens['access'], tokens['refresh']).status_code == 200

    response = _logout(tokens['access'], tokens['refresh'])

    assert response.status_code == 400
    assert BlacklistedToken.objects.filter(token__user=user).count() == 1


def test_logout_requires_token_field(user):
    access = issue_token_pair(user)['access']
    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION=f'Bearer {access}')

    response = client.post(LOGOUT_URL, {}, format='json')

    assert response.status_code == 400
    assert 'refresh' in response.data['errors']


# --- 12-hour inactivity ------------------------------------------------------

IDLE = int(timedelta(hours=12).total_seconds())


def _now():
    return datetime_to_epoch(timezone.now())


def _session_token(user, *, idle_for, claim=True):
    """
    A tracked refresh token for a session whose last user activity was
    `idle_for` seconds ago. claim=False mimics a token issued before the
    last_activity claim existed (7-day lifetime, activity = iat).
    """
    token = RefreshToken.for_user(user)
    if claim:
        token[LAST_ACTIVITY_CLAIM] = _now() - idle_for
    else:
        token['iat'] = _now() - idle_for
        token['exp'] = _now() + int(timedelta(days=6).total_seconds())
    return str(token)


def _claims(raw):
    return RefreshToken(raw, verify=False).payload


def test_session_idle_timeout_is_12_hours():
    assert settings.SESSION_IDLE_TIMEOUT == timedelta(hours=12)


def test_login_refresh_token_lasts_one_idle_window(user):
    claims = _claims(issue_token_pair(user)['refresh'])
    assert claims['exp'] - claims['iat'] == IDLE


def test_active_session_refreshes_within_the_window(user):
    raw = _session_token(user, idle_for=IDLE - 60)

    response = _refresh(raw, idle_seconds=5)

    assert response.status_code == 200, response.data
    claims = _claims(response.data['data']['refresh'])
    # Activity 5 seconds ago: a fresh 12-hour window from then.
    assert abs(claims[LAST_ACTIVITY_CLAIM] - (_now() - 5)) <= 2
    assert claims['exp'] == claims[LAST_ACTIVITY_CLAIM] + IDLE


def test_session_idle_for_12_hours_cannot_refresh(user):
    raw = _session_token(user, idle_for=IDLE + 1)

    response = _refresh(raw, idle_seconds=0)

    assert response.status_code == 401
    assert response.data['errors']['code'] == 'session_expired'
    assert not OutstandingToken.objects.filter(user=user, blacklistedtoken__isnull=True).exclude(
        jti=RefreshToken(raw, verify=False)['jti']
    ).exists()


def test_legacy_token_is_idle_from_its_issue_time(user):
    # Issued 13 hours ago with the old 7-day lifetime: still unexpired by
    # exp, but past the idle window.
    raw = _session_token(user, idle_for=IDLE + 3600, claim=False)

    response = _refresh(raw, idle_seconds=0)

    assert response.status_code == 401
    assert response.data['errors']['code'] == 'session_expired'


def test_refresh_without_activity_does_not_extend_the_session(user):
    # Background polling: refreshes, but claims no user activity.
    raw = _session_token(user, idle_for=IDLE - 3600)
    before = _claims(raw)[LAST_ACTIVITY_CLAIM]

    for _ in range(3):
        response = _refresh(raw)
        assert response.status_code == 200, response.data
        raw = response.data['data']['refresh']

    claims = _claims(raw)
    assert claims[LAST_ACTIVITY_CLAIM] == before
    # Still ends 12 hours after the last real activity.
    assert claims['exp'] == before + IDLE


def test_idle_seconds_cannot_move_activity_backwards(user):
    raw = _session_token(user, idle_for=600)
    before = _claims(raw)[LAST_ACTIVITY_CLAIM]

    response = _refresh(raw, idle_seconds=IDLE * 10)

    assert response.status_code == 200
    assert _claims(response.data['data']['refresh'])[LAST_ACTIVITY_CLAIM] == before


def test_idle_seconds_must_not_be_negative(user):
    response = _refresh(issue_token_pair(user)['refresh'], idle_seconds=-60)

    assert response.status_code == 400
    assert 'idle_seconds' in response.data['errors']
