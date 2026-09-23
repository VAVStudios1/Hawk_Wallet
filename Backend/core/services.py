import secrets
from datetime import timedelta

from django.conf import settings
from django.utils import timezone

from core.models import OTP
from core.sms import get_sms_provider


class OTPServiceError(Exception):
    """Base class for all OTP service errors."""


class OTPCooldownError(OTPServiceError):
    def __init__(self, retry_after_seconds):
        self.retry_after_seconds = retry_after_seconds
        super().__init__('Please wait before requesting another OTP.')


class OTPNotFoundError(OTPServiceError):
    def __init__(self):
        super().__init__('No active OTP found. Please request a new one.')


class OTPExpiredError(OTPServiceError):
    def __init__(self):
        super().__init__('This OTP has expired. Please request a new one.')


class OTPMaxAttemptsError(OTPServiceError):
    def __init__(self):
        super().__init__('Too many incorrect attempts. Please request a new OTP.')


class OTPInvalidError(OTPServiceError):
    def __init__(self):
        super().__init__('Incorrect OTP.')


def _generate_numeric_code(length):
    return ''.join(secrets.choice('0123456789') for _ in range(length))


def generate_and_send_otp(user, purpose):
    """
    Invalidates any prior unused OTP of this purpose for the user, creates a
    fresh one, and dispatches it via the configured SMS provider. Enforces a
    resend cooldown based on the most recently created OTP of this purpose.
    """
    cooldown_seconds = settings.OTP_RESEND_COOLDOWN_SECONDS
    last_otp = (
        OTP.objects.filter(user=user, purpose=purpose)
        .order_by('-created_at')
        .first()
    )
    if last_otp is not None:
        elapsed = (timezone.now() - last_otp.created_at).total_seconds()
        if elapsed < cooldown_seconds:
            raise OTPCooldownError(retry_after_seconds=int(cooldown_seconds - elapsed))

    OTP.objects.filter(user=user, purpose=purpose, is_used=False).update(is_used=True)

    code = _generate_numeric_code(settings.OTP_LENGTH)
    otp = OTP(
        user=user,
        purpose=purpose,
        expires_at=timezone.now() + timedelta(minutes=settings.OTP_EXPIRY_MINUTES),
    )
    otp.set_code(code)
    otp.save()

    get_sms_provider().send_otp(user.mobile_number, code, purpose)
    return otp


def verify_otp(user, purpose, code):
    """
    Verifies the most recent, unused OTP of the given purpose for the user.
    Raises an OTPServiceError subclass on any failure; returns the OTP on
    success and marks it used (one-time-use).
    """
    otp = (
        OTP.objects.filter(user=user, purpose=purpose, is_used=False)
        .order_by('-created_at')
        .first()
    )
    if otp is None:
        raise OTPNotFoundError()

    if otp.is_expired():
        raise OTPExpiredError()

    if otp.attempts >= settings.OTP_MAX_ATTEMPTS:
        raise OTPMaxAttemptsError()

    if not otp.check_code(code):
        otp.attempts += 1
        otp.save(update_fields=['attempts'])
        if otp.attempts >= settings.OTP_MAX_ATTEMPTS:
            raise OTPMaxAttemptsError()
        raise OTPInvalidError()

    otp.is_used = True
    otp.save(update_fields=['is_used'])
    return otp
