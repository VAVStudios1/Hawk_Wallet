"""
Forgot password: mobile number -> OTP -> single-use reset token -> new password.

1. request_password_reset: sends a PASSWORD_RESET OTP through the shared OTP
   service (hashed, expiring, attempt-limited, resend cooldown, plus the
   per-account caps in settings.OTP_REQUEST_LIMITS). The outcome is never
   reported to the caller, so the endpoint cannot be used to learn which
   mobile numbers have accounts.
2. verify_password_reset_otp: consumes the OTP and returns a random reset
   token. Only its SHA-256 hash is stored (PasswordResetToken), tied to the
   user, the OTP and a fingerprint of the user's current password hash. It
   expires after PASSWORD_RESET_TOKEN_EXPIRY_MINUTES; issuing one voids any
   earlier live token for the user.
3. confirm_password_reset: under the user's row lock, checks the token,
   validates and sets the new password, marks the token used, revokes every
   refresh token and writes a PASSWORD_RESET audit event. It returns no
   tokens: the user logs in again with the new password.

Only active, verified accounts can reset. Any failure to verify the OTP is
reported the same way, whether or not the account exists.
"""
import hashlib
import secrets
from datetime import timedelta

from django.conf import settings
from django.contrib.auth.password_validation import validate_password
from django.core.exceptions import ValidationError
from django.db import transaction
from django.utils import timezone
from django.utils.crypto import constant_time_compare, salted_hmac

from audit.models import AuditEvent
from audit.services import record_audit_event
from core.models import OTP, PasswordResetToken, User
from core.services import OTPServiceError, generate_and_send_otp, verify_otp
from core.tokens import revoke_all_refresh_tokens


class PasswordResetError(Exception):
    """Base for password-reset errors. `code` is stable for API mapping."""

    code = 'password_reset_error'
    default_message = 'The password could not be reset.'

    def __init__(self, message=None, errors=None):
        super().__init__(message or self.default_message)
        self.errors = errors or {}


class InvalidResetOTP(PasswordResetError):
    code = 'otp_invalid'
    default_message = 'The OTP is invalid or has expired. Please request a new one.'


class InvalidResetToken(PasswordResetError):
    code = 'reset_token_invalid'
    default_message = 'This password reset has expired or was already used. Please start again.'


class InvalidNewPassword(PasswordResetError):
    code = 'invalid_new_password'
    default_message = 'New password does not meet the password rules.'


def request_password_reset(*, mobile_number):
    """
    Send a reset OTP if `mobile_number` belongs to an active, verified
    account and its cooldown and request caps allow it. Returns nothing and
    raises nothing for any of those cases.
    """
    user = _resettable_user(mobile_number)
    if user is None:
        return
    with transaction.atomic():
        # Serializes requests for one account so the caps cannot be raced.
        locked = User.objects.select_for_update().get(pk=user.pk)
        try:
            generate_and_send_otp(locked, OTP.Purpose.PASSWORD_RESET)
        except OTPServiceError:
            pass


def verify_password_reset_otp(*, mobile_number, code):
    """Consume the reset OTP; returns the raw reset token (shown once)."""
    user = _resettable_user(mobile_number)
    if user is None:
        raise InvalidResetOTP()
    try:
        otp = verify_otp(user, OTP.Purpose.PASSWORD_RESET, code)
    except OTPServiceError:
        raise InvalidResetOTP()

    raw_token = secrets.token_urlsafe(32)
    now = timezone.now()
    with transaction.atomic():
        locked = User.objects.select_for_update().get(pk=user.pk)
        _live_tokens(locked).update(invalidated_at=now)
        PasswordResetToken.objects.create(
            user=locked,
            otp=otp,
            token_hash=_hash_token(raw_token),
            password_fingerprint=_password_fingerprint(locked),
            expires_at=now + timedelta(minutes=settings.PASSWORD_RESET_TOKEN_EXPIRY_MINUTES),
        )
    return raw_token


def confirm_password_reset(*, reset_token, new_password, ip_address=None):
    """Set a new password with a valid reset token. Returns nothing."""
    record = PasswordResetToken.objects.filter(token_hash=_hash_token(reset_token)).first()
    if record is None:
        raise InvalidResetToken()

    with transaction.atomic():
        # The same row lock token rotation takes (core.tokens.lock_user), so
        # no refresh can mint a token the revocation below misses.
        user = User.objects.select_for_update().get(pk=record.user_id)
        record = PasswordResetToken.objects.select_for_update().get(pk=record.pk)
        if (
            record.used_at is not None
            or record.invalidated_at is not None
            or record.is_expired()
            or not (user.is_active and user.is_verified)
            or not constant_time_compare(record.password_fingerprint, _password_fingerprint(user))
        ):
            raise InvalidResetToken()

        try:
            validate_password(new_password, user=user)
        except ValidationError as exc:
            # The token stays usable, so the user can pick another password.
            raise InvalidNewPassword(errors={'new_password': list(exc.messages)})

        user.set_password(new_password)
        user.save(update_fields=['password'])
        record.used_at = timezone.now()
        record.save(update_fields=['used_at'])
        revoked = revoke_all_refresh_tokens(user)
        record_audit_event(
            actor=user,
            action=AuditEvent.Action.PASSWORD_RESET,
            target=user,
            ip_address=ip_address,
            metadata={'revoked_refresh_tokens': revoked},
        )


def _resettable_user(mobile_number):
    return User.objects.filter(
        mobile_number=mobile_number, is_active=True, is_verified=True
    ).first()


def _live_tokens(user):
    return PasswordResetToken.objects.filter(user=user, used_at__isnull=True, invalidated_at__isnull=True)


def _hash_token(raw_token):
    return hashlib.sha256(raw_token.encode()).hexdigest()


def _password_fingerprint(user):
    # Changes whenever the password does (reset or profile change), which
    # voids any reset token issued before it. Keyed with SECRET_KEY, so it
    # reveals nothing about the password hash.
    return salted_hmac('core.password_reset', user.password, algorithm='sha256').hexdigest()
