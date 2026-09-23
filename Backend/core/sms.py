"""
SMS delivery abstraction for OTP messages.

Keeps OTP generation/verification (core/services.py) decoupled from how the
code is actually delivered, so a paid provider (Twilio, MSG91, Firebase, ...)
can be plugged in later without touching the OTP business logic - only
`get_sms_provider()` and a new provider class change.
"""

from django.conf import settings


class SMSProvider:
    def send_otp(self, mobile_number, code, purpose):
        raise NotImplementedError


class ConsoleSMSProvider(SMSProvider):
    """
    Development-only provider. Never sends a real SMS - it prints the OTP
    straight to the server terminal so it can be read during local testing.
    This is the "safe development mechanism" for seeing OTPs without a paid
    SMS vendor.
    """

    def send_otp(self, mobile_number, code, purpose):
        print(
            '==================================================\n'
            'DEV OTP\n'
            f'Mobile: {mobile_number}\n'
            f'Purpose: {purpose}\n'
            f'OTP: {code}\n'
            '=================================================='
        )


def get_sms_provider():
    provider_name = getattr(settings, 'SMS_PROVIDER', 'console')
    if provider_name == 'console':
        return ConsoleSMSProvider()
    raise NotImplementedError(
        f"SMS provider '{provider_name}' is not configured yet."
    )
