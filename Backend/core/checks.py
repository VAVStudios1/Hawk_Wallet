"""
Startup checks for OTP SMS delivery, run by `manage.py check`, runserver and
migrate. ConsoleSMSProvider also refuses to send without DEBUG at runtime;
these catch the misconfiguration before a deployment goes live.
"""

from django.conf import settings
from django.core.checks import Error, register


@register()
def check_sms_provider(app_configs, **kwargs):
    provider = getattr(settings, 'SMS_PROVIDER', 'console')
    if provider == 'console':
        if not settings.DEBUG:
            return [Error(
                'SMS_PROVIDER is "console" with DEBUG off: OTPs cannot be delivered.',
                hint='Set SMS_PROVIDER=2factor and TWOFACTOR_API_KEY in the environment.',
                id='core.E001',
            )]
        return []
    if provider == '2factor':
        errors = []
        if not settings.TWOFACTOR_API_KEY:
            errors.append(Error(
                'SMS_PROVIDER is "2factor" but TWOFACTOR_API_KEY is empty.',
                id='core.E002',
            ))
        if not settings.TWOFACTOR_BASE_URL.startswith('https://'):
            errors.append(Error(
                'TWOFACTOR_BASE_URL must be an https:// URL.',
                id='core.E003',
            ))
        return errors
    return [Error(
        f'Unknown SMS_PROVIDER "{provider}".',
        hint='Use "console" (local development) or "2factor".',
        id='core.E004',
    )]
