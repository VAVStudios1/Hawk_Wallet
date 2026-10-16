"""
SMS delivery abstraction for OTP messages.

Keeps OTP generation/verification (core/services.py) decoupled from how the
code is actually delivered. Django always generates, hashes and verifies the
code itself; a provider only delivers it.

A provider's send_otp() returns the provider's message reference (or '') on
success and raises SMSDeliveryError on any failure. It must never put the
OTP, an API key, a request URL or the full mobile number into an exception
or a log line.
"""

import http.client
import json
import urllib.error
import urllib.request
from urllib.parse import quote

from django.conf import settings
from django.core.exceptions import ImproperlyConfigured


class SMSDeliveryError(Exception):
    """
    The OTP SMS was not sent, or could not be confirmed as sent. `reason` is
    a short fixed code, safe to log and store.

    `uncertain` is True when the provider may still deliver the message (a
    timeout after the request went out, or an unreadable reply). Such a send
    must never be retried automatically.
    """

    def __init__(self, reason, uncertain=False):
        self.reason = reason
        self.uncertain = uncertain
        super().__init__(f'OTP SMS delivery failed ({reason}).')


class SMSProvider:
    def send_otp(self, mobile_number, code, purpose):
        raise NotImplementedError


class ConsoleSMSProvider(SMSProvider):
    """
    Development-only provider. Never sends a real SMS - it prints the OTP
    straight to the server terminal so it can be read during local testing.
    It refuses to run unless DEBUG is on, so a production server that falls
    back to it fails the send instead of printing codes into server logs.
    """

    def send_otp(self, mobile_number, code, purpose):
        if not settings.DEBUG:
            raise SMSDeliveryError('console_provider_disabled')
        print(
            '==================================================\n'
            'DEV OTP\n'
            f'Mobile: {mobile_number}\n'
            f'Purpose: {purpose}\n'
            f'OTP: {code}\n'
            '=================================================='
        )
        return ''


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    # The API key is in the URL path, so never follow a redirect elsewhere.
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def _open(request, timeout):
    return urllib.request.build_opener(_NoRedirect).open(request, timeout=timeout)


def _parse(body):
    try:
        payload = json.loads(body.decode('utf-8'))
    except (UnicodeDecodeError, ValueError):
        return None
    return payload if isinstance(payload, dict) else None


def _rejection_reason(status, payload):
    details = str((payload or {}).get('Details', '')).lower()
    if 'api key' in details or 'apikey' in details:
        return 'invalid_api_key'
    if 'balance' in details or 'credit' in details:
        return 'insufficient_balance'
    if 'template' in details:
        return 'template_rejected'
    if 'phone' in details or 'number' in details:
        return 'invalid_phone_number'
    if 'otp' in details:
        return 'invalid_otp_format'
    if status >= 500:
        return 'provider_unavailable'
    if 300 <= status < 400:
        return 'unexpected_redirect'
    return 'rejected'


class TwoFactorSMSProvider(SMSProvider):
    """
    2Factor.in "Send OTP (Manual Generation)", as documented at
    https://2factor.in/API/DOCS/SMS_OTP.html:

        POST {base_url}/{api_key}/SMS/{+91XXXXXXXXXX}/{otp}[/{template_name}]

    The template segment is optional (absent = the account's default OTP
    template).

    Success is HTTP 200 with {"Status": "Success", "Details": "<session id>"};
    errors are {"Status": "Error", "Details": "<reason>"}, usually HTTP 400.
    2Factor's AUTOGEN and VERIFY endpoints are deliberately not used: the
    code is generated and verified only by Django.

    2Factor puts the API key and the OTP in the URL path, so the URL is never
    logged, and errors are raised without the underlying exception chained.
    """

    def __init__(self, api_key, base_url, template_name='', timeout=10):
        self.api_key = api_key
        self.base_url = (base_url or '').rstrip('/')
        self.template_name = template_name
        self.timeout = timeout

    def _url(self, mobile_number, code):
        segments = [
            quote(self.api_key, safe=''),
            'SMS',
            quote(f'+91{mobile_number}', safe='+'),
            quote(code, safe=''),
        ]
        if self.template_name:
            segments.append(quote(self.template_name, safe=''))
        return f'{self.base_url}/' + '/'.join(segments)

    def send_otp(self, mobile_number, code, purpose):
        if not self.api_key or not self.base_url.startswith('https://'):
            raise SMSDeliveryError('not_configured')

        # Everything travels in the path; the empty body sends Content-Length: 0.
        request = urllib.request.Request(
            self._url(mobile_number, code),
            data=b'',
            method='POST',
            headers={'Accept': 'application/json'},
        )
        try:
            with _open(request, self.timeout) as response:
                status, body = response.status, response.read(4096)
        except urllib.error.HTTPError as exc:
            # 2Factor answered with an error status: the SMS was not sent.
            try:
                body = exc.read(4096)
            except Exception:
                body = b''
            finally:
                exc.close()
            raise SMSDeliveryError(_rejection_reason(exc.code, _parse(body))) from None
        except urllib.error.URLError:
            # Raised while connecting or sending: no complete request reached 2Factor.
            raise SMSDeliveryError('connection_failed') from None
        except TimeoutError:
            # The request went out but no reply came: it may still be delivered.
            raise SMSDeliveryError('timeout', uncertain=True) from None
        except (OSError, http.client.HTTPException):
            raise SMSDeliveryError('no_response', uncertain=True) from None

        payload = _parse(body)
        if payload is None:
            raise SMSDeliveryError('invalid_response', uncertain=True)
        if status == 200 and payload.get('Status') == 'Success':
            return str(payload.get('Details', ''))[:64]
        raise SMSDeliveryError(_rejection_reason(status, payload))


def get_sms_provider():
    provider_name = getattr(settings, 'SMS_PROVIDER', 'console')
    if provider_name == 'console':
        return ConsoleSMSProvider()
    if provider_name == '2factor':
        return TwoFactorSMSProvider(
            api_key=settings.TWOFACTOR_API_KEY,
            base_url=settings.TWOFACTOR_BASE_URL,
            template_name=settings.TWOFACTOR_OTP_TEMPLATE,
            timeout=settings.TWOFACTOR_TIMEOUT_SECONDS,
        )
    raise ImproperlyConfigured(f"Unknown SMS_PROVIDER '{provider_name}'.")
