"""
Refresh-token lifecycle on top of SimpleJWT's token_blacklist app.

Login (VerifyLoginOTPView) issues pairs with RefreshToken.for_user, which
records each refresh token as an OutstandingToken. From there:

- rotate_refresh_token: SimpleJWT's own TokenRefreshSerializer, which
  blacklists the used token and issues a tracked replacement
  (ROTATE_REFRESH_TOKENS + BLACKLIST_AFTER_ROTATION). It runs under a lock
  on the user's row so two refreshes of one token, or a refresh racing a
  password change, cannot both succeed.
- blacklist_own_refresh_token: logout of one refresh token, only the
  caller's own.
- revoke_all_refresh_tokens: blacklists every live refresh token a user
  has (password change).

Access tokens are stateless and are not revoked: one already issued stays
valid until it expires (ACCESS_TOKEN_LIFETIME).
"""
from django.contrib.auth import get_user_model
from django.db import transaction
from django.utils import timezone
from rest_framework.exceptions import AuthenticationFailed
from rest_framework_simplejwt.exceptions import TokenError
from rest_framework_simplejwt.serializers import TokenRefreshSerializer
from rest_framework_simplejwt.settings import api_settings
from rest_framework_simplejwt.token_blacklist.models import BlacklistedToken, OutstandingToken
from rest_framework_simplejwt.tokens import RefreshToken

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


class RefreshTokenNotOwned(AuthTokenError):
    code = 'token_not_owned'
    default_message = 'This refresh token does not belong to you.'


def issue_token_pair(user):
    """A new, tracked refresh token and its access token for `user`."""
    refresh = RefreshToken.for_user(user)
    return {'access': str(refresh.access_token), 'refresh': str(refresh)}


def rotate_refresh_token(raw_token):
    """
    Exchange a valid refresh token for a new pair; the old one is
    blacklisted. Only tokens recorded as outstanding are accepted, so every
    refresh token that works can also be revoked.
    """
    token = _verified_refresh_token(raw_token)
    with transaction.atomic():
        lock_user(token.payload.get(api_settings.USER_ID_CLAIM))
        if not OutstandingToken.objects.filter(jti=token[api_settings.JTI_CLAIM]).exists():
            raise InvalidRefreshToken()

        # Re-validated under the lock: a concurrent refresh, logout or
        # password change may have blacklisted the token meanwhile.
        serializer = TokenRefreshSerializer(data={'refresh': raw_token})
        try:
            serializer.is_valid(raise_exception=True)
        except (TokenError, AuthenticationFailed):
            raise InvalidRefreshToken()
        return {
            'access': serializer.validated_data['access'],
            'refresh': serializer.validated_data['refresh'],
        }


def blacklist_own_refresh_token(*, user, raw_token):
    """
    Logout: blacklist `raw_token` if it is a valid refresh token belonging
    to `user`. Another user's token is refused and left untouched.
    """
    token = _verified_refresh_token(raw_token)
    if str(token.payload.get(api_settings.USER_ID_CLAIM)) != str(user.pk):
        raise RefreshTokenNotOwned()
    token.blacklist()


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
