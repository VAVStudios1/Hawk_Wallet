"""
Refresh-token lifecycle on top of SimpleJWT's token_blacklist app.

Login (core/login.py) issues pairs with issue_token_pair, which records each
refresh token as an OutstandingToken. From there:

- rotate_refresh_token: exchanges a refresh token for a new pair and
  blacklists the used one (single-use refresh tokens). It runs under a lock
  on the user's row so two refreshes of one token, or a refresh racing a
  password change or reset, cannot both succeed.
- blacklist_refresh_token: logout of one refresh token.
- revoke_all_refresh_tokens: blacklists every live refresh token a user
  has (password change, password reset).

Session inactivity (settings.SESSION_IDLE_TIMEOUT): each refresh token
carries a `last_activity` claim - the last time the user actually used the
app (a token without it dates from its `iat`, i.e. login). A refresh is
refused once that is SESSION_IDLE_TIMEOUT or more in the past, and every
rotated token expires exactly SESSION_IDLE_TIMEOUT after it. Rotation moves
`last_activity` forward only when the client reports recent user activity
(`idle_seconds`); a refresh made by background polling carries the old value
over, so it keeps the session alive only until the idle window runs out.

Access tokens are stateless and are not revoked: one already issued stays
valid until it expires (ACCESS_TOKEN_LIFETIME).
"""
from django.conf import settings
from django.contrib.auth import get_user_model
from django.db import transaction
from django.utils import timezone
from rest_framework_simplejwt.exceptions import TokenError
from rest_framework_simplejwt.settings import api_settings
from rest_framework_simplejwt.token_blacklist.models import BlacklistedToken, OutstandingToken
from rest_framework_simplejwt.tokens import RefreshToken
from rest_framework_simplejwt.utils import datetime_from_epoch, datetime_to_epoch

User = get_user_model()


class AuthTokenError(Exception):
    """Base for refresh-token errors. `code` is stable for API mapping."""

    code = 'token_error'
    default_message = 'The token could not be processed.'

    def __init__(self, message=None):
        super().__init__(message or self.default_message)


class InvalidRefreshToken(AuthTokenError):
    code = 'token_not_valid'
    default_message = 'Refresh token is invalid, expired or revoked.'


class SessionIdleExpired(InvalidRefreshToken):
    code = 'session_expired'
    default_message = 'Your session has expired due to inactivity. Please log in again.'


LAST_ACTIVITY_CLAIM = 'last_activity'


def issue_token_pair(user):
    """
    A new, tracked refresh token and its access token for `user`. Issuing
    counts as activity: the session's idle window starts now.
    """
    refresh = RefreshToken.for_user(user)
    return {'access': str(refresh.access_token), 'refresh': str(refresh)}


def rotate_refresh_token(raw_token, idle_seconds=None):
    """
    Exchange a valid refresh token for a new pair; the old one is
    blacklisted. Only tokens recorded as outstanding are accepted, so every
    refresh token that works can also be revoked.

    `idle_seconds` is how long ago the user last interacted with the app, as
    the client reports it; None means no activity is claimed. It can only
    move the session's last activity forward, never past now, and never
    revive a session whose idle window has already run out.
    """
    token = _verified_refresh_token(raw_token)
    with transaction.atomic():
        user = lock_user(token.payload.get(api_settings.USER_ID_CLAIM))
        jti = token[api_settings.JTI_CLAIM]
        if not OutstandingToken.objects.filter(jti=jti).exists():
            raise InvalidRefreshToken()
        # Re-checked under the lock: a concurrent refresh, logout or
        # password change may have blacklisted the token meanwhile.
        if BlacklistedToken.objects.filter(token__jti=jti).exists():
            raise InvalidRefreshToken()
        if not api_settings.USER_AUTHENTICATION_RULE(user):
            raise InvalidRefreshToken()

        idle_limit = int(settings.SESSION_IDLE_TIMEOUT.total_seconds())
        now = datetime_to_epoch(timezone.now())
        last_activity = session_last_activity(token)
        if now - last_activity >= idle_limit:
            raise SessionIdleExpired()
        if idle_seconds is not None:
            last_activity = max(last_activity, now - max(int(idle_seconds), 0))

        token.blacklist()
        token[LAST_ACTIVITY_CLAIM] = last_activity
        token.set_jti()
        token.set_iat()
        token.set_exp(from_time=datetime_from_epoch(last_activity), lifetime=settings.SESSION_IDLE_TIMEOUT)
        token.outstand()
        return {'access': str(token.access_token), 'refresh': str(token)}


def session_last_activity(token):
    """Epoch seconds of the session's last user activity, per the token."""
    return int(token.payload.get(LAST_ACTIVITY_CLAIM, token['iat']))


def blacklist_refresh_token(raw_token):
    """
    Logout: blacklist `raw_token` if it is a valid refresh token. The token
    itself is the credential - whoever holds it could use it, so revoking it
    grants nothing - which lets a client log out after its access token has
    expired.
    """
    _verified_refresh_token(raw_token).blacklist()


def revoke_all_refresh_tokens(user):
    """
    Blacklist every unexpired, not yet blacklisted refresh token issued to
    `user`; returns how many there were. Call inside a transaction that
    holds the user's row lock (lock_user), so a refresh cannot mint a new
    token that this sweep misses.
    """
    live = list(
        OutstandingToken.objects.filter(
            user=user, expires_at__gt=timezone.now(), blacklistedtoken__isnull=True
        )
    )
    BlacklistedToken.objects.bulk_create(
        [BlacklistedToken(token=token) for token in live], ignore_conflicts=True
    )
    return len(live)


def lock_user(user_id):
    """
    Row-lock the user that refresh tokens are rotated or revoked for (a no-op
    on SQLite, which serializes writers at the database level instead).
    """
    try:
        return User.objects.select_for_update().get(**{api_settings.USER_ID_FIELD: user_id})
    except (User.DoesNotExist, TypeError, ValueError):
        raise InvalidRefreshToken()


def _verified_refresh_token(raw_token):
    # Checks signature, expiry, token type and the blacklist.
    try:
        return RefreshToken(raw_token)
    except TokenError:
        raise InvalidRefreshToken()
