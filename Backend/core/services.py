import logging
import math
import re
import secrets
from datetime import timedelta

from django.conf import settings
from django.db import transaction
from django.utils import timezone

from core.models import OTP, OTPSendAttempt, User
from core.sms import SMSDeliveryError, get_sms_provider
from core.utils import mask_mobile

logger = logging.getLogger(__name__)


class OTPServiceError(Exception):
    """Base class for all OTP service errors. `code` is stable for API mapping."""

    code = 'otp_error'


class OTPCooldownError(OTPServiceError):
    code = 'otp_cooldown'

    def __init__(self, retry_after_seconds):
        self.retry_after_seconds = retry_after_seconds
        super().__init__('Please wait before requesting another OTP.')


class OTPRequestLimitError(OTPServiceError):
    code = 'otp_request_limit'

    def __init__(self, retry_after_seconds=None):
        self.retry_after_seconds = retry_after_seconds
        super().__init__('Too many OTP requests. Please try again later.')


class OTPDeliveryError(OTPServiceError):
    """The SMS was not sent, or could not be confirmed as sent."""

    code = 'otp_delivery_failed'

    def __init__(self, uncertain=False):
        self.uncertain = uncertain
        super().__init__(
            'We could not confirm the OTP was sent. If it does not arrive, '
            'request a new one after the wait.'
            if uncertain
            else 'We could not send the OTP right now. Please try again later.'
        )


class OTPNotFoundError(OTPServiceError):
    code = 'otp_not_found'

    def __init__(self):
        super().__init__('No active OTP found. Please request a new one.')


class OTPExpiredError(OTPServiceError):
    code = 'otp_expired'

    def __init__(self):
        super().__init__('This OTP has expired. Please request a new one.')


class OTPMaxAttemptsError(OTPServiceError):
    code = 'otp_max_attempts'

    def __init__(self):
        super().__init__('Too many incorrect attempts. Please request a new OTP.')


class OTPInvalidError(OTPServiceError):
    code = 'otp_invalid'

    def __init__(self):
        super().__init__('Incorrect OTP.')


def _generate_numeric_code(length):
    return ''.join(secrets.choice('0123456789') for _ in range(length))


def normalize_mobile_number(value):
    """10-digit Indian mobile number: drops separators and a +91 / 91 / 0 prefix."""
    digits = re.sub(r'\D', '', value or '')
    if len(digits) == 12 and digits.startswith('91'):
        digits = digits[2:]
    elif len(digits) == 11 and digits.startswith('0'):
        digits = digits[1:]
    return digits


def _send_limit_error(mobile_number, purpose, now):
    """
    The error that blocks an OTP SMS to this number now, or None. Counts
    every recorded send attempt for the number, whatever its purpose or
    outcome:
    - at most OTP_SEND_WINDOW_MAX sends in any rolling OTP_SEND_WINDOW_SECONDS;
    - OTP_SEND_COOLDOWN_SECONDS since the last send;
    - the per-purpose caps in OTP_REQUEST_LIMITS.
    """
    attempts = OTPSendAttempt.objects.filter(mobile_number=mobile_number)

    window_start = now - timedelta(seconds=settings.OTP_SEND_WINDOW_SECONDS)
    in_window = list(
        attempts.filter(created_at__gt=window_start)
        .order_by('-created_at')
        .values_list('created_at', flat=True)[: settings.OTP_SEND_WINDOW_MAX]
    )
    if len(in_window) >= settings.OTP_SEND_WINDOW_MAX:
        # Allowed again once the oldest of these leaves the window.
        frees_at = in_window[-1] + timedelta(seconds=settings.OTP_SEND_WINDOW_SECONDS)
        return OTPRequestLimitError(
            retry_after_seconds=max(math.ceil((frees_at - now).total_seconds()), 1)
        )

    last_sent_at = attempts.order_by('-created_at').values_list('created_at', flat=True).first()
    if last_sent_at is not None:
        elapsed = (now - last_sent_at).total_seconds()
        if elapsed < settings.OTP_SEND_COOLDOWN_SECONDS:
            return OTPCooldownError(
                retry_after_seconds=math.ceil(settings.OTP_SEND_COOLDOWN_SECONDS - elapsed)
            )

    for window_seconds, max_requests in settings.OTP_REQUEST_LIMITS.get(purpose, ()):
        since = now - timedelta(seconds=window_seconds)
        if attempts.filter(purpose=purpose, created_at__gt=since).count() >= max_requests:
            return OTPRequestLimitError()
    return None


def _store_otp(user, purpose, code):
    # Only the newest OTP of a purpose is ever live.
    with transaction.atomic():
        OTP.objects.filter(user=user, purpose=purpose, is_used=False).update(is_used=True)
        otp = OTP(
            user=user,
            purpose=purpose,
            expires_at=timezone.now() + timedelta(minutes=settings.OTP_EXPIRY_MINUTES),
        )
        otp.set_code(code)
        otp.save()
    return otp


def _finish_attempt(attempt, status, *, reason='', reference=''):
    attempt.status = status
    attempt.failure_reason = reason
    attempt.provider_reference = reference
    attempt.completed_at = timezone.now()
    attempt.save(update_fields=['status', 'failure_reason', 'provider_reference', 'completed_at'])


def generate_and_send_otp(user, purpose):
    """
    Sends the user a fresh OTP for `purpose` and stores its hash.

    1. Under a lock on the user's row (mobile_number is unique, so this
       serializes sends per number), checks the per-number send limits and
       records the send attempt. That transaction commits before the SMS goes
       out, so an in-flight, failed or uncertain send still counts. Callers
       must therefore not wrap this call in their own transaction.
    2. Asks the provider to deliver a Django-generated code.
    3. Only once the code was (or may have been) delivered is it stored,
       replacing the previous live OTP. A send that definitely failed leaves
       the previous OTP valid.

    Raises OTPCooldownError / OTPRequestLimitError when a limit blocks the
    send, OTPDeliveryError when it failed or could not be confirmed. Never
    retries.
    """
    mobile_number = normalize_mobile_number(user.mobile_number)
    masked = mask_mobile(mobile_number)

    with transaction.atomic():
        list(User.objects.select_for_update().filter(pk=user.pk).values_list('pk', flat=True))
        now = timezone.now()
        error = _send_limit_error(mobile_number, purpose, now)
        if error is not None:
            logger.info('OTP SMS refused by %s: mobile=%s purpose=%s', error.code, masked, purpose)
            raise error
        attempt = OTPSendAttempt.objects.create(
            user=user, mobile_number=mobile_number, purpose=purpose, created_at=now
        )

    code = _generate_numeric_code(settings.OTP_LENGTH)
    try:
        reference = get_sms_provider().send_otp(mobile_number, code, purpose) or ''
    except SMSDeliveryError as exc:
        status = OTPSendAttempt.Status.UNCERTAIN if exc.uncertain else OTPSendAttempt.Status.FAILED
        _finish_attempt(attempt, status, reason=exc.reason)
        logger.warning(
            'OTP SMS %s: mobile=%s purpose=%s reason=%s', status, masked, purpose, exc.reason
        )
        if exc.uncertain:
            # It may still arrive, so the code it carries must work.
            _store_otp(user, purpose, code)
        raise OTPDeliveryError(uncertain=exc.uncertain) from None
    except Exception as exc:
        _finish_attempt(attempt, OTPSendAttempt.Status.FAILED, reason='provider_error')
        logger.error(
            'OTP SMS failed: mobile=%s purpose=%s reason=provider_error (%s)',
            masked, purpose, type(exc).__name__,
        )
        raise OTPDeliveryError() from None

    otp = _store_otp(user, purpose, code)
    _finish_attempt(attempt, OTPSendAttempt.Status.SENT, reference=reference[:64])
    logger.info('OTP SMS sent: mobile=%s purpose=%s', masked, purpose)
    return otp


def verify_otp(user, purpose, code):
    """
    Verifies the most recent, unused OTP of the given purpose for the user.
    Raises an OTPServiceError subclass on any failure; returns the OTP on
    success and marks it used (one-time-use).

    The OTP row is locked while it is checked, so two concurrent correct
    submissions cannot both succeed and concurrent wrong guesses cannot lose
    attempt increments. The failure is raised only after the transaction
    commits, so a wrong guess is always counted.
    """
    error = None
    with transaction.atomic():
        otp = (
            OTP.objects.select_for_update()
            .filter(user=user, purpose=purpose, is_used=False)
            .order_by('-created_at')
            .first()
        )
        if otp is None:
            error = OTPNotFoundError()
        elif otp.is_expired():
            error = OTPExpiredError()
        elif otp.attempts >= settings.OTP_MAX_ATTEMPTS:
            error = OTPMaxAttemptsError()
        elif not otp.check_code(code):
            otp.attempts += 1
            otp.save(update_fields=['attempts'])
            if otp.attempts >= settings.OTP_MAX_ATTEMPTS:
                error = OTPMaxAttemptsError()
            else:
                error = OTPInvalidError()
        else:
            otp.is_used = True
            otp.save(update_fields=['is_used'])
    if error is not None:
        raise error
    return otp
