"""
OTP SMS delivery (core/sms.py) and the per-number send limits in
core/services.py: 150 s cooldown and at most 2 sends in any rolling 5
minutes, shared by every OTP purpose and endpoint. Every test uses a fake
provider or a patched transport; nothing reaches the network.
"""
import io
import json
import logging
import threading
import urllib.error
from datetime import timedelta
from types import SimpleNamespace

import pytest
from django.core.cache import cache
from django.db import connection
from rest_framework.test import APIClient

from core import sms as sms_module
from core.checks import check_sms_provider
from core.models import OTP, OTPSendAttempt, User
from core.services import (
    OTPCooldownError,
    OTPDeliveryError,
    OTPExpiredError,
    OTPMaxAttemptsError,
    OTPNotFoundError,
    OTPRequestLimitError,
    OTPServiceError,
    generate_and_send_otp,
    normalize_mobile_number,
    verify_otp,
)
from core.sms import ConsoleSMSProvider, SMSDeliveryError, TwoFactorSMSProvider

PASSWORD = 'unused-password-123'
API_KEY = 'a1b2c3d4-test-key-9f8e7d6c'
REG = OTP.Purpose.REGISTRATION
RESET = OTP.Purpose.PASSWORD_RESET

postgres_only = pytest.mark.skipif(
    connection.vendor != 'postgresql',
    reason='needs real row locks (SELECT ... FOR UPDATE)',
)


@pytest.fixture(autouse=True)
def _clear_throttles():
    cache.clear()


class FakeProvider:
    """Stands in for get_sms_provider(); records every send, can be made to fail."""

    def __init__(self):
        self.sent = []
        self.fail = None

    def __call__(self):
        return self

    def send_otp(self, mobile_number, code, purpose):
        self.sent.append(SimpleNamespace(mobile_number=mobile_number, code=code, purpose=purpose))
        if self.fail is not None:
            raise self.fail
        return 'session-ref-1'


@pytest.fixture
def fake_sms(monkeypatch):
    fake = FakeProvider()
    monkeypatch.setattr('core.services.get_sms_provider', fake)
    return fake


def _post(url, body, ip='10.0.0.1'):
    return APIClient(REMOTE_ADDR=ip).post(url, body, format='json')


def _wrong(code):
    return '000000' if code != '000000' else '111111'


# --- sending and storage -----------------------------------------------------


def test_send_stores_only_a_hash_and_records_the_attempt(user, fake_sms, clock):
    otp = generate_and_send_otp(user, REG)

    code = fake_sms.sent[0].code
    assert len(code) == 6 and code.isdigit()
    assert fake_sms.sent[0].mobile_number == user.mobile_number
    assert code not in otp.code_hash and otp.check_code(code)
    assert otp.expires_at == clock.now + timedelta(minutes=5)
    attempt = OTPSendAttempt.objects.get()
    assert (attempt.status, attempt.purpose, attempt.mobile_number) == (
        OTPSendAttempt.Status.SENT, REG, user.mobile_number,
    )
    assert attempt.provider_reference == 'session-ref-1'
    assert attempt.created_at == clock.now


@pytest.mark.parametrize('raw', ['9876543210', '+91 98765 43210', '919876543210', '09876543210'])
def test_mobile_numbers_are_normalized(raw):
    assert normalize_mobile_number(raw) == '9876543210'


# --- per-number limits -------------------------------------------------------


def test_resend_is_denied_at_149_seconds_and_allowed_at_150(user, fake_sms, clock):
    generate_and_send_otp(user, REG)

    clock.advance(seconds=149)
    with pytest.raises(OTPCooldownError) as denied:
        generate_and_send_otp(user, REG)
    assert denied.value.retry_after_seconds == 1

    clock.advance(seconds=1)
    generate_and_send_otp(user, REG)
    assert len(fake_sms.sent) == 2


def test_cooldown_and_rolling_window_timeline(user, fake_sms, clock):
    # 10:00 allowed
    generate_and_send_otp(user, REG)
    # 10:01 cooldown
    clock.advance(seconds=60)
    with pytest.raises(OTPCooldownError) as cooldown:
        generate_and_send_otp(user, REG)
    assert cooldown.value.retry_after_seconds == 90
    # 10:02:30 allowed
    clock.advance(seconds=90)
    generate_and_send_otp(user, REG)
    # 10:04 third send inside 5 minutes
    clock.advance(seconds=90)
    with pytest.raises(OTPRequestLimitError) as window:
        generate_and_send_otp(user, REG)
    assert window.value.retry_after_seconds == 60
    # 10:05 the 10:00 send has left the window
    clock.advance(seconds=60)
    generate_and_send_otp(user, REG)

    assert len(fake_sms.sent) == 3
    assert OTPSendAttempt.objects.count() == 3


def test_limits_are_shared_across_purposes(user, fake_sms, clock):
    generate_and_send_otp(user, REG)
    clock.advance(seconds=60)

    with pytest.raises(OTPCooldownError):
        generate_and_send_otp(user, RESET)

    clock.advance(seconds=90)
    generate_and_send_otp(user, RESET)
    clock.advance(seconds=150)
    # 10:05 is free again for either purpose; 10:04 was not.
    generate_and_send_otp(user, REG)
    assert [s.purpose for s in fake_sms.sent] == [REG, RESET, REG]


def test_limits_are_shared_across_endpoints_and_ips(db, fake_sms, clock):
    assert _post('/api/auth/register/', {
        'name': 'New Member', 'mobile_number': '9876543210',
        'password': PASSWORD, 'confirm_password': PASSWORD,
    }).data['data']['otp_sent'] is True

    clock.advance(seconds=60)
    resend = _post('/api/auth/resend-otp/', {'mobile_number': '9876543210', 'purpose': 'registration'}, ip='10.0.0.2')
    assert resend.status_code == 429
    assert resend.data['errors'] == {'code': 'otp_cooldown', 'retry_after_seconds': 90}

    login = _post('/api/auth/login/', {'mobile_number': '9876543210', 'password': PASSWORD}, ip='10.0.0.3')
    assert login.status_code == 403
    assert login.data['errors']['otp_sent'] is False

    clock.advance(seconds=90)
    assert _post('/api/auth/resend-otp/', {'mobile_number': '9876543210', 'purpose': 'registration'}, ip='10.0.0.4').status_code == 200

    clock.advance(seconds=150)
    # 10:05: allowed again through yet another endpoint.
    login = _post('/api/auth/login/', {'mobile_number': '9876543210', 'password': PASSWORD})
    assert login.data['errors']['otp_sent'] is True
    assert len(fake_sms.sent) == 3


def test_window_limit_is_reported_with_retry_after(db, fake_sms, clock, make_user):
    account = make_user(is_verified=False)
    generate_and_send_otp(account, REG)
    clock.advance(seconds=150)
    generate_and_send_otp(account, REG)
    clock.advance(seconds=100)

    response = _post('/api/auth/resend-otp/', {'mobile_number': account.mobile_number, 'purpose': 'registration'})

    assert response.status_code == 429
    assert response.data['errors'] == {'code': 'otp_request_limit', 'retry_after_seconds': 50}


def test_failed_sends_count_against_the_limits(user, fake_sms, clock):
    fake_sms.fail = SMSDeliveryError('rejected')
    with pytest.raises(OTPDeliveryError):
        generate_and_send_otp(user, REG)

    fake_sms.fail = None
    clock.advance(seconds=60)
    with pytest.raises(OTPCooldownError):
        generate_and_send_otp(user, REG)
    assert OTPSendAttempt.objects.get().status == OTPSendAttempt.Status.FAILED


@postgres_only
@pytest.mark.django_db(transaction=True)
def test_concurrent_requests_send_only_one_sms(monkeypatch):
    account = User.objects.create_user(
        mobile_number='9876500001', name='Racer', password=PASSWORD, is_verified=False
    )
    fake = FakeProvider()
    monkeypatch.setattr('core.services.get_sms_provider', fake)
    barrier = threading.Barrier(5)
    outcomes = []

    def request_otp():
        barrier.wait()
        try:
            generate_and_send_otp(account, REG)
            outcomes.append('sent')
        except OTPServiceError as exc:
            outcomes.append(exc.code)
        finally:
            connection.close()

    threads = [threading.Thread(target=request_otp) for _ in range(5)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()

    assert outcomes.count('sent') == 1
    assert outcomes.count('otp_cooldown') == 4
    assert len(fake.sent) == 1
    assert OTPSendAttempt.objects.filter(mobile_number='9876500001').count() == 1


# --- delivery failures -------------------------------------------------------


def test_failed_replacement_keeps_the_existing_otp_valid(user, fake_sms, clock):
    generate_and_send_otp(user, REG)
    first_code = fake_sms.sent[0].code
    clock.advance(seconds=150)
    fake_sms.fail = SMSDeliveryError('insufficient_balance')

    with pytest.raises(OTPDeliveryError) as failed:
        generate_and_send_otp(user, REG)

    assert failed.value.uncertain is False
    assert OTP.objects.filter(user=user).count() == 1
    verify_otp(user, REG, first_code)


def test_uncertain_send_keeps_its_code_usable_and_is_not_retried(user, fake_sms, clock):
    generate_and_send_otp(user, REG)
    clock.advance(seconds=150)
    fake_sms.fail = SMSDeliveryError('timeout', uncertain=True)

    with pytest.raises(OTPDeliveryError) as uncertain:
        generate_and_send_otp(user, REG)

    assert uncertain.value.uncertain is True
    assert len(fake_sms.sent) == 2  # one provider call, no retry
    attempt = OTPSendAttempt.objects.latest('created_at')
    assert (attempt.status, attempt.failure_reason) == (OTPSendAttempt.Status.UNCERTAIN, 'timeout')
    verify_otp(user, REG, fake_sms.sent[1].code)


def test_unexpected_provider_exception_is_a_generic_failure(user, fake_sms):
    fake_sms.fail = RuntimeError(f'boom {API_KEY}')

    with pytest.raises(OTPDeliveryError) as failed:
        generate_and_send_otp(user, REG)

    assert API_KEY not in str(failed.value)
    assert failed.value.__cause__ is None
    attempt = OTPSendAttempt.objects.get()
    assert (attempt.status, attempt.failure_reason) == (OTPSendAttempt.Status.FAILED, 'provider_error')
    assert not OTP.objects.exists()


def test_failed_registration_stays_unverified_and_recoverable(db, fake_sms, clock):
    fake_sms.fail = SMSDeliveryError('rejected')

    response = _post('/api/auth/register/', {
        'name': 'New Member', 'mobile_number': '9876543210',
        'password': PASSWORD, 'confirm_password': PASSWORD,
    })

    assert response.status_code == 201
    assert response.data['data'] == {'mobile_number': '9876543210', 'otp_sent': False}
    assert User.objects.get(mobile_number='9876543210').is_verified is False

    fake_sms.fail = None
    clock.advance(seconds=150)
    assert _post('/api/auth/resend-otp/', {'mobile_number': '9876543210', 'purpose': 'registration'}).status_code == 200
    verified = _post('/api/auth/verify-otp/', {'mobile_number': '9876543210', 'otp': fake_sms.sent[-1].code})
    assert verified.status_code == 200
    assert User.objects.get(mobile_number='9876543210').is_verified is True


@pytest.mark.parametrize('uncertain', [False, True])
def test_resend_delivery_failure_is_a_generic_503(make_user, fake_sms, uncertain):
    account = make_user(is_verified=False)
    fake_sms.fail = SMSDeliveryError('insufficient_balance', uncertain=uncertain)

    response = _post('/api/auth/resend-otp/', {'mobile_number': account.mobile_number, 'purpose': 'registration'})

    assert response.status_code == 503
    assert response.data['success'] is False
    assert response.data['errors'] == {'code': 'otp_delivery_failed'}
    assert 'balance' not in response.data['message'].lower()


def test_unverified_login_reports_a_failed_sms(make_user, fake_sms):
    account = make_user(is_verified=False)
    fake_sms.fail = SMSDeliveryError('connection_failed')

    response = _post('/api/auth/login/', {'mobile_number': account.mobile_number, 'password': PASSWORD})

    assert response.status_code == 403
    assert response.data['errors']['code'] == 'account_not_verified'
    assert response.data['errors']['otp_sent'] is False


def test_password_reset_delivery_failure_reveals_nothing(user, fake_sms):
    fake_sms.fail = SMSDeliveryError('invalid_api_key')

    known = _post('/api/auth/password-reset/request/', {'mobile_number': user.mobile_number})
    unknown = _post('/api/auth/password-reset/request/', {'mobile_number': '9999999999'})

    assert known.status_code == unknown.status_code == 200
    assert known.data == unknown.data


# --- verification rules ------------------------------------------------------


def test_correct_otp_verifies_once(user, fake_sms):
    generate_and_send_otp(user, REG)
    code = fake_sms.sent[0].code

    verify_otp(user, REG, code)
    with pytest.raises(OTPNotFoundError):
        verify_otp(user, REG, code)


def test_otp_expires_after_five_minutes(user, fake_sms, clock):
    generate_and_send_otp(user, REG)
    code = fake_sms.sent[0].code

    clock.advance(minutes=5)
    with pytest.raises(OTPExpiredError):
        verify_otp(user, REG, code)


def test_otp_is_valid_just_before_expiry(user, fake_sms, clock):
    generate_and_send_otp(user, REG)

    clock.advance(seconds=299)
    verify_otp(user, REG, fake_sms.sent[0].code)


def test_five_wrong_attempts_lock_the_otp(user, fake_sms):
    generate_and_send_otp(user, REG)
    code = fake_sms.sent[0].code
    for _ in range(4):
        with pytest.raises(OTPServiceError) as wrong:
            verify_otp(user, REG, _wrong(code))
        assert wrong.value.code == 'otp_invalid'
    with pytest.raises(OTPMaxAttemptsError):
        verify_otp(user, REG, _wrong(code))

    with pytest.raises(OTPMaxAttemptsError):
        verify_otp(user, REG, code)


# --- 2Factor provider --------------------------------------------------------


class _Response:
    def __init__(self, status, body):
        self.status = status
        self._body = body

    def read(self, size=-1):
        return self._body

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


def _http_error(status, payload):
    body = json.dumps(payload).encode()
    return urllib.error.HTTPError('https://2factor.in/hidden', status, 'Error', {}, io.BytesIO(body))


@pytest.fixture
def transport(monkeypatch):
    """Replaces the HTTP call; `result` is a response or an exception to raise."""
    calls = []
    state = SimpleNamespace(calls=calls, result=_Response(200, b'{"Status": "Success", "Details": "sess-42"}'))

    def fake_open(request, timeout):
        calls.append(SimpleNamespace(
            url=request.full_url, method=request.get_method(), body=request.data, timeout=timeout,
        ))
        if isinstance(state.result, BaseException):
            raise state.result
        return state.result

    monkeypatch.setattr(sms_module, '_open', fake_open)
    return state


def _two_factor(**overrides):
    options = dict(api_key=API_KEY, base_url='https://2factor.in/API/V1', template_name='HawkOTP', timeout=7)
    options.update(overrides)
    return TwoFactorSMSProvider(**options)


def test_two_factor_sends_the_django_code_by_post(transport):
    reference = _two_factor().send_otp('9876543210', '123456', REG)

    assert reference == 'sess-42'
    call = transport.calls[0]
    assert call.method == 'POST'
    assert call.body == b''
    assert call.url == f'https://2factor.in/API/V1/{API_KEY}/SMS/+919876543210/123456/HawkOTP'
    assert call.timeout == 7
    assert 'AUTOGEN' not in call.url and 'VERIFY' not in call.url


def test_two_factor_without_template_uses_the_account_default(transport):
    _two_factor(template_name='').send_otp('9876543210', '123456', REG)

    assert transport.calls[0].url.endswith('/SMS/+919876543210/123456')


@pytest.mark.parametrize('result, reason, uncertain', [
    (_http_error(400, {'Status': 'Error', 'Details': 'Invalid API Key'}), 'invalid_api_key', False),
    (_http_error(400, {'Status': 'Error', 'Details': 'Insufficient Balance'}), 'insufficient_balance', False),
    (_http_error(400, {'Status': 'Error', 'Details': 'Invalid Phone Number - Length Mismatch(Expected: 10)'}),
     'invalid_phone_number', False),
    (_http_error(400, {'Status': 'Error', 'Details': 'Template not approved'}), 'template_rejected', False),
    (_http_error(503, {}), 'provider_unavailable', False),
    (_Response(200, b'{"Status": "Error", "Details": "Request refused"}'), 'rejected', False),
    (urllib.error.URLError(ConnectionRefusedError()), 'connection_failed', False),
    (urllib.error.URLError(TimeoutError()), 'connection_failed', False),
    (TimeoutError(), 'timeout', True),
    (ConnectionResetError(), 'no_response', True),
    (_Response(200, b'<html>gateway</html>'), 'invalid_response', True),
])
def test_two_factor_failures(transport, result, reason, uncertain):
    transport.result = result

    with pytest.raises(SMSDeliveryError) as failed:
        _two_factor().send_otp('9876543210', '123456', REG)

    assert (failed.value.reason, failed.value.uncertain) == (reason, uncertain)
    assert len(transport.calls) == 1  # never retried
    text = f'{failed.value} {failed.value!r}'
    assert API_KEY not in text and '123456' not in text and '9876543210' not in text
    # No underlying exception (which could carry the URL) is attached.
    assert failed.value.__cause__ is None
    assert failed.value.__context__ is None or failed.value.__suppress_context__


@pytest.mark.parametrize('overrides', [{'api_key': ''}, {'base_url': 'http://2factor.in/API/V1'}])
def test_two_factor_refuses_to_send_when_misconfigured(transport, overrides):
    with pytest.raises(SMSDeliveryError) as failed:
        _two_factor(**overrides).send_otp('9876543210', '123456', REG)

    assert failed.value.reason == 'not_configured'
    assert transport.calls == []


def test_get_sms_provider_builds_two_factor_from_settings(settings):
    settings.SMS_PROVIDER = '2factor'
    settings.TWOFACTOR_API_KEY = API_KEY
    settings.TWOFACTOR_OTP_TEMPLATE = 'HawkOTP'

    provider = sms_module.get_sms_provider()

    assert isinstance(provider, TwoFactorSMSProvider)
    assert (provider.api_key, provider.template_name) == (API_KEY, 'HawkOTP')


def test_logs_never_contain_the_otp_key_or_full_number(user, settings, transport, clock, caplog):
    settings.SMS_PROVIDER = '2factor'
    settings.TWOFACTOR_API_KEY = API_KEY
    caplog.set_level(logging.DEBUG)

    generate_and_send_otp(user, REG)  # sent
    clock.advance(seconds=150)
    transport.result = _http_error(400, {'Status': 'Error', 'Details': 'Insufficient Balance'})
    with pytest.raises(OTPDeliveryError):
        generate_and_send_otp(user, REG)  # failed
    clock.advance(seconds=60)
    with pytest.raises(OTPRequestLimitError):
        generate_and_send_otp(user, REG)  # refused
    clock.advance(seconds=90)
    transport.result = TimeoutError()
    with pytest.raises(OTPDeliveryError):
        generate_and_send_otp(user, REG)  # uncertain

    codes = [call.url.rsplit('/', 1)[-1] for call in transport.calls]
    assert len(codes) == 3
    assert 'sent' in caplog.text and 'insufficient_balance' in caplog.text and 'timeout' in caplog.text
    assert API_KEY not in caplog.text
    assert user.mobile_number not in caplog.text
    assert '******' + user.mobile_number[-4:] in caplog.text
    for code in codes:
        assert code not in caplog.text


# --- console provider and configuration checks -------------------------------


def test_test_runs_cannot_reach_the_live_provider():
    # conftest._no_real_sms overrides whatever .env selects.
    from django.conf import settings

    assert settings.SMS_PROVIDER == 'console'
    assert settings.TWOFACTOR_API_KEY == ''
    with pytest.raises(RuntimeError, match='Network access is disabled'):
        _two_factor().send_otp('9876543210', '123456', REG)


def test_console_provider_refuses_without_debug(settings, capsys):
    settings.DEBUG = False

    with pytest.raises(SMSDeliveryError) as failed:
        ConsoleSMSProvider().send_otp('9876543210', '424242', REG)

    assert failed.value.reason == 'console_provider_disabled'
    assert '424242' not in capsys.readouterr().out


def test_console_provider_prints_in_local_development(settings, capsys):
    settings.DEBUG = True

    ConsoleSMSProvider().send_otp('9876543210', '424242', REG)

    assert '424242' in capsys.readouterr().out


def test_production_console_fallback_fails_the_send_without_printing(user, settings, capsys):
    settings.DEBUG = False
    settings.SMS_PROVIDER = 'console'

    with pytest.raises(OTPDeliveryError):
        generate_and_send_otp(user, REG)

    assert 'OTP' not in capsys.readouterr().out
    assert not OTP.objects.exists()


@pytest.mark.parametrize('config, error_ids', [
    ({'DEBUG': False, 'SMS_PROVIDER': 'console'}, ['core.E001']),
    ({'DEBUG': True, 'SMS_PROVIDER': 'console'}, []),
    ({'SMS_PROVIDER': '2factor', 'TWOFACTOR_API_KEY': ''}, ['core.E002']),
    ({'SMS_PROVIDER': '2factor', 'TWOFACTOR_API_KEY': API_KEY, 'TWOFACTOR_BASE_URL': 'http://x'}, ['core.E003']),
    ({'SMS_PROVIDER': '2factor', 'TWOFACTOR_API_KEY': API_KEY}, []),
    ({'SMS_PROVIDER': 'twilio'}, ['core.E004']),
])
def test_sms_provider_system_check(settings, config, error_ids):
    for name, value in config.items():
        setattr(settings, name, value)

    assert [error.id for error in check_sms_provider(None)] == error_ids
