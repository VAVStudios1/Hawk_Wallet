"""
Password login: mobile number + password -> JWT pair. No OTP.

Password login is the only login factor, so wrong passwords are counted per
account: LOGIN_MAX_FAILED_ATTEMPTS failures within
LOGIN_FAILURE_WINDOW_MINUTES lock password login for LOGIN_LOCKOUT_MINUTES.
While locked, the password is not even checked. Attempts on one account run
under a lock on its row, so parallel guesses cannot slip past the limit. A
successful password check clears the counter. The per-IP 'login' throttle
applies on top.

Every attempt on an existing account is audited (LOGIN_SUCCEEDED /
LOGIN_FAILED); metadata never holds the password.
"""
from datetime import timedelta

from django.conf import settings
from django.contrib.auth import authenticate
from django.contrib.auth.models import update_last_login
from django.db import transaction
from django.utils import timezone

from audit.models import AuditEvent
from audit.services import record_audit_event
from core.models import OTP, User
from core.services import OTPServiceError, generate_and_send_otp
from core.tokens import issue_token_pair


class LoginError(Exception):
    """Base for login errors. `code` is stable for API mapping."""

    code = 'login_error'
    default_message = 'Login failed.'

    def __init__(self, message=None, errors=None):
        super().__init__(message or self.default_message)
        self.errors = errors or {}


class InvalidCredentials(LoginError):
    # Also used for an inactive account, so it is not told apart.
    code = 'invalid_credentials'
    default_message = 'Invalid mobile number or password.'


class AccountLocked(LoginError):
    code = 'account_locked'
    default_message = 'Too many failed login attempts. Please try again later.'

    def __init__(self, retry_after_seconds):
        super().__init__(errors={'retry_after_seconds': retry_after_seconds})


class AccountNotVerified(LoginError):
    code = 'account_not_verified'
    default_message = 'Please verify your mobile number to complete registration.'


def login_with_password(*, mobile_number, password, request=None, ip_address=None):
    """
    Returns {'access', 'refresh', 'user'} for an active, verified account
    whose password matches; raises a LoginError subclass otherwise.

    An unverified account with the right password gets no tokens and no
    login OTP. A registration OTP is sent (as before) so the client can go
    straight to registration verification; errors['otp_sent'] says whether
    one went out.
    """
    user = User.objects.filter(mobile_number=mobile_number).first()
    if user is None:
        # Runs the password hasher once anyway, so an unknown number takes
        # about as long as a wrong password.
        authenticate(request, mobile_number=mobile_number, password=password)
        raise InvalidCredentials()

    error = None
    with transaction.atomic():
        locked = User.objects.select_for_update().get(pk=user.pk)
        now = timezone.now()

        if locked.login_locked_until and locked.login_locked_until > now:
            _audit_failure(locked, ip_address, reason='locked')
            error = AccountLocked(_seconds_until(locked.login_locked_until, now))
        elif authenticate(request, mobile_number=mobile_number, password=password) is None:
            # authenticate() refuses inactive accounts even with the right
            # password; counting those too keeps the two indistinguishable.
            reason = 'invalid_password' if locked.is_active else 'inactive'
            locked_until = _record_failure(locked, now)
            _audit_failure(locked, ip_address, reason=reason, locked=locked_until is not None)
            error = (
                AccountLocked(_seconds_until(locked_until, now))
                if locked_until is not None
                else InvalidCredentials()
            )
        else:
            _clear_failures(locked)
            if not locked.is_verified:
                _audit_failure(locked, ip_address, reason='not_verified')
                error = AccountNotVerified()
            else:
                update_last_login(None, locked)
                record_audit_event(
                    actor=locked,
                    action=AuditEvent.Action.LOGIN_SUCCEEDED,
                    target=locked,
                    ip_address=ip_address,
                )
                tokens = issue_token_pair(locked)

    # Raised after commit, so failure counts and audit rows are kept.
    if isinstance(error, AccountNotVerified):
        error.errors = {'otp_sent': _send_registration_otp(user)}
    if error is not None:
        raise error

    return {
        **tokens,
        'user': {
            'id': locked.id,
            'name': locked.name,
            'mobile_number': locked.mobile_number,
            'role': locked.role,
        },
    }


def _record_failure(user, now):
    """Count one wrong password; returns the lock expiry if this locks the account."""
    window = timedelta(minutes=settings.LOGIN_FAILURE_WINDOW_MINUTES)
    if user.last_failed_login_at is None or now - user.last_failed_login_at > window:
        user.failed_login_attempts = 0
    user.failed_login_attempts += 1
    user.last_failed_login_at = now

    locked_until = None
    if user.failed_login_attempts >= settings.LOGIN_MAX_FAILED_ATTEMPTS:
        locked_until = now + timedelta(minutes=settings.LOGIN_LOCKOUT_MINUTES)
        user.login_locked_until = locked_until
        user.failed_login_attempts = 0
    user.save(update_fields=['failed_login_attempts', 'last_failed_login_at', 'login_locked_until'])
    return locked_until


def _clear_failures(user):
    if user.failed_login_attempts or user.last_failed_login_at or user.login_locked_until:
        user.failed_login_attempts = 0
        user.last_failed_login_at = None
        user.login_locked_until = None
        user.save(update_fields=['failed_login_attempts', 'last_failed_login_at', 'login_locked_until'])


def _audit_failure(user, ip_address, *, reason, locked=False):
    # No actor: whoever tried is not known to be the account holder.
    record_audit_event(
        actor=None,
        action=AuditEvent.Action.LOGIN_FAILED,
        target=user,
        ip_address=ip_address,
        metadata={'reason': reason, 'locked': locked},
    )


def _send_registration_otp(user):
    try:
        generate_and_send_otp(user, OTP.Purpose.REGISTRATION)
    except OTPServiceError:
        return False
    return True


def _seconds_until(moment, now):
    return max(int((moment - now).total_seconds()), 1)
