"""
API tests for password login (no OTP), registration OTP, failed-login
lockout and the forgot-password flow under /api/auth/.
"""
import hashlib
from datetime import timedelta
from types import SimpleNamespace

import pytest
from django.core.cache import cache
from django.utils import timezone
from rest_framework.test import APIClient

from audit.models import AuditEvent
from core.models import OTP, PasswordResetToken, User
from core.services import generate_and_send_otp
from core.tokens import issue_token_pair

PASSWORD = 'unused-password-123'  # conftest make_user's password
NEW_PASSWORD = 'Brand-new-pass-42'

REGISTER_URL = '/api/auth/register/'
VERIFY_OTP_URL = '/api/auth/verify-otp/'
RESEND_OTP_URL = '/api/auth/resend-otp/'
LOGIN_URL = '/api/auth/login/'
REFRESH_URL = '/api/auth/token/refresh/'
RESET_REQUEST_URL = '/api/auth/password-reset/request/'
RESET_VERIFY_URL = '/api/auth/password-reset/verify/'
RESET_CONFIRM_URL = '/api/auth/password-reset/confirm/'


@pytest.fixture(autouse=True)
def _clear_throttles():
    # Throttle history lives in the cache and would leak between tests.
    cache.clear()


@pytest.fixture
def sms(monkeypatch):
    """Captures every OTP the service sends instead of printing it."""
    sent = []

    class CapturingProvider:
        def send_otp(self, mobile_number, code, purpose):
            sent.append(SimpleNamespace(mobile_number=mobile_number, code=code, purpose=purpose))

    monkeypatch.setattr('core.services.get_sms_provider', CapturingProvider)
    return sent


def _post(url, body):
    return APIClient().post(url, body, format='json')


def _login(user_or_mobile, password=PASSWORD):
    mobile = getattr(user_or_mobile, 'mobile_number', user_or_mobile)
    return _post(LOGIN_URL, {'mobile_number': mobile, 'password': password})


def _audit(user, action):
    return AuditEvent.objects.filter(action=action, target_type='core.User', target_id=str(user.pk))


# --- A-E: password login -----------------------------------------------------


def test_login_with_correct_password_returns_tokens_and_user(user):
    response = _login(user)

    assert response.status_code == 200, response.data
    data = response.data['data']
    assert set(data) == {'access', 'refresh', 'user'}
    assert data['user'] == {
        'id': user.id,
        'name': user.name,
        'mobile_number': user.mobile_number,
        'role': user.role,
    }
    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION=f"Bearer {data['access']}")
    assert client.get('/api/profile/').status_code == 200
    assert _refresh(data['refresh']).status_code == 200

    user.refresh_from_db()
    assert user.last_login is not None
    event = _audit(user, AuditEvent.Action.LOGIN_SUCCEEDED).get()
    assert event.actor == user


def test_login_does_not_request_an_otp(user, sms):
    assert _login(user).status_code == 200

    assert sms == []
    assert not OTP.objects.exists()


def test_wrong_password_fails(user):
    response = _login(user, 'not-the-password')

    assert response.status_code == 401
    assert response.data['success'] is False
    assert response.data['errors']['code'] == 'invalid_credentials'
    assert 'access' not in response.data.get('data', {})
    user.refresh_from_db()
    assert user.failed_login_attempts == 1
    event = _audit(user, AuditEvent.Action.LOGIN_FAILED).get()
    assert event.actor is None
    assert event.metadata == {'reason': 'invalid_password', 'locked': False}
    assert 'not-the-password' not in str(event.metadata)


def test_unknown_mobile_gets_the_same_answer_as_a_wrong_password(user):
    wrong = _login(user, 'not-the-password')
    unknown = _login('9999999999', 'not-the-password')

    assert unknown.status_code == wrong.status_code == 401
    assert unknown.data == wrong.data


def test_inactive_user_cannot_login(make_user):
    inactive = make_user(is_active=False)

    response = _login(inactive)

    # Indistinguishable from a wrong password.
    assert response.status_code == 401
    assert response.data['errors']['code'] == 'invalid_credentials'


def test_unverified_user_cannot_login_and_gets_no_login_otp(make_user, sms):
    unverified = make_user(is_verified=False)

    response = _login(unverified)

    assert response.status_code == 403
    assert response.data['errors']['code'] == 'account_not_verified'
    assert 'access' not in response.data.get('data', {})
    assert not OTP.objects.filter(purpose=OTP.Purpose.LOGIN).exists()
    # Verification stays with the registration OTP.
    assert [s.purpose for s in sms] == [OTP.Purpose.REGISTRATION]
    assert response.data['errors']['otp_sent'] is True


def test_unverified_user_with_wrong_password_learns_nothing(make_user, sms):
    unverified = make_user(is_verified=False)

    response = _login(unverified, 'not-the-password')

    assert response.status_code == 401
    assert sms == []


# --- F-G: registration OTP is unchanged ---------------------------------------


def _register(mobile='9876543210'):
    return _post(REGISTER_URL, {
        'name': 'New Member',
        'mobile_number': mobile,
        'password': PASSWORD,
        'confirm_password': PASSWORD,
    })


def test_registration_sends_otp_and_requires_verification(db, sms):
    response = _register()

    assert response.status_code == 201, response.data
    created = User.objects.get(mobile_number='9876543210')
    assert created.role == User.Role.MEMBER
    assert created.is_verified is False
    assert [(s.mobile_number, s.purpose) for s in sms] == [('9876543210', OTP.Purpose.REGISTRATION)]
    assert 'access' not in response.data['data']

    assert _login(created).status_code == 403


def test_registration_otp_verification_then_login(db, sms):
    _register()

    wrong = _post(VERIFY_OTP_URL, {'mobile_number': '9876543210', 'otp': '000000' if sms[0].code != '000000' else '111111'})
    assert wrong.status_code == 400

    response = _post(VERIFY_OTP_URL, {'mobile_number': '9876543210', 'otp': sms[0].code})

    assert response.status_code == 200, response.data
    assert User.objects.get(mobile_number='9876543210').is_verified is True
    assert _login('9876543210').status_code == 200


def test_resend_otp_still_works_for_registration(db, sms, clock):
    _register()
    clock.advance(seconds=150)

    response = _post(RESEND_OTP_URL, {'mobile_number': '9876543210', 'purpose': 'registration'})

    assert response.status_code == 200, response.data
    assert len(sms) == 2


@pytest.mark.parametrize('purpose', ['login', 'password_reset'])
def test_resend_otp_refuses_login_and_reset_purposes(user, sms, purpose):
    response = _post(RESEND_OTP_URL, {'mobile_number': user.mobile_number, 'purpose': purpose})

    assert response.status_code == 400
    assert sms == []


# --- Q: failed-login lockout -------------------------------------------------


def test_five_wrong_passwords_lock_the_account(user):
    for _ in range(4):
        assert _login(user, 'wrong-password').status_code == 401

    fifth = _login(user, 'wrong-password')

    assert fifth.status_code == 429
    assert fifth.data['errors']['code'] == 'account_locked'
    assert 0 < fifth.data['errors']['retry_after_seconds'] <= 15 * 60

    # Even the right password is refused while locked.
    locked = _login(user)
    assert locked.status_code == 429
    assert locked.data['errors']['code'] == 'account_locked'
    assert _audit(user, AuditEvent.Action.LOGIN_FAILED).filter(metadata__reason='locked').exists()


def test_lock_expires_and_success_resets_the_counter(user):
    for _ in range(5):
        _login(user, 'wrong-password')
    User.objects.filter(pk=user.pk).update(login_locked_until=timezone.now() - timedelta(seconds=1))

    assert _login(user).status_code == 200

    user.refresh_from_db()
    assert user.failed_login_attempts == 0
    assert user.login_locked_until is None


def test_successful_login_resets_failed_attempts(user):
    for _ in range(3):
        _login(user, 'wrong-password')
    assert _login(user).status_code == 200

    for _ in range(3):
        assert _login(user, 'wrong-password').status_code == 401


def test_failures_outside_the_window_do_not_accumulate(user):
    for _ in range(4):
        _login(user, 'wrong-password')
    User.objects.filter(pk=user.pk).update(last_failed_login_at=timezone.now() - timedelta(minutes=16))

    assert _login(user, 'wrong-password').status_code == 401
    user.refresh_from_db()
    assert user.failed_login_attempts == 1


def test_lockout_is_per_account(make_user):
    victim, bystander = make_user(), make_user()
    for _ in range(5):
        _login(victim, 'wrong-password')

    assert _login(bystander).status_code == 200


# --- H-P: forgot password ----------------------------------------------------


GENERIC_REQUEST_REPLY = {
    'success': True,
    'message': 'If an account exists for this mobile number, an OTP has been sent.',
    'data': {},
}


def _request_reset(mobile):
    return _post(RESET_REQUEST_URL, {'mobile_number': mobile})


def _verify_reset(mobile, code):
    return _post(RESET_VERIFY_URL, {'mobile_number': mobile, 'otp': code})


def _confirm_reset(token, password=NEW_PASSWORD, confirm=None):
    return _post(RESET_CONFIRM_URL, {
        'reset_token': token,
        'new_password': password,
        'confirm_password': password if confirm is None else confirm,
    })


def _reset_token_for(user, sms):
    assert _request_reset(user.mobile_number).status_code == 200
    response = _verify_reset(user.mobile_number, sms[-1].code)
    assert response.status_code == 200, response.data
    return response.data['data']['reset_token']


def _wrong_code(right):
    return '000000' if right != '000000' else '111111'


def test_reset_request_sends_a_password_reset_otp(user, sms):
    response = _request_reset(user.mobile_number)

    assert response.status_code == 200
    assert response.data == GENERIC_REQUEST_REPLY
    assert [(s.mobile_number, s.purpose) for s in sms] == [(user.mobile_number, OTP.Purpose.PASSWORD_RESET)]
    otp = OTP.objects.get(user=user, purpose=OTP.Purpose.PASSWORD_RESET)
    assert otp.code_hash != sms[0].code  # stored hashed


def test_reset_request_for_unknown_mobile_reveals_nothing(user, sms):
    response = _request_reset('9999999999')

    assert response.status_code == 200
    assert response.data == GENERIC_REQUEST_REPLY
    assert sms == []
    assert not OTP.objects.exists()


@pytest.mark.parametrize('extra', [{'is_verified': False}, {'is_active': False}])
def test_reset_request_for_ineligible_account_reveals_nothing(make_user, sms, extra):
    account = make_user(**extra)

    response = _request_reset(account.mobile_number)

    assert response.data == GENERIC_REQUEST_REPLY
    assert sms == []


def test_reset_request_cooldown_and_caps_are_silent(user, sms, clock):
    # One request per cooldown stays inside the per-number SMS limits, so
    # only the hourly password-reset cap (5) stops the sixth.
    for _ in range(5):
        assert _request_reset(user.mobile_number).data == GENERIC_REQUEST_REPLY
        clock.advance(seconds=150)

    capped = _request_reset(user.mobile_number)

    assert capped.status_code == 200
    assert capped.data == GENERIC_REQUEST_REPLY
    assert OTP.objects.filter(user=user, purpose=OTP.Purpose.PASSWORD_RESET).count() == 5
    assert len(sms) == 5
    # Only the newest OTP is live.
    assert OTP.objects.filter(user=user, purpose=OTP.Purpose.PASSWORD_RESET, is_used=False).count() == 1


def test_reset_request_cooldown_blocks_immediate_resend(user, sms):
    _request_reset(user.mobile_number)

    assert _request_reset(user.mobile_number).data == GENERIC_REQUEST_REPLY
    assert len(sms) == 1


def test_correct_reset_otp_returns_single_use_token(user, sms):
    _request_reset(user.mobile_number)

    response = _verify_reset(user.mobile_number, sms[0].code)

    assert response.status_code == 200, response.data
    token = response.data['data']['reset_token']
    assert response.data['data']['expires_in_seconds'] == 600
    record = PasswordResetToken.objects.get(user=user)
    assert record.token_hash == hashlib.sha256(token.encode()).hexdigest()
    assert token not in record.token_hash
    assert sms[0].code not in token
    assert record.otp.is_used is True


def test_wrong_reset_otp_fails(user, sms):
    _request_reset(user.mobile_number)

    response = _verify_reset(user.mobile_number, _wrong_code(sms[0].code))

    assert response.status_code == 400
    assert response.data['errors']['code'] == 'otp_invalid'
    assert not PasswordResetToken.objects.exists()


def test_expired_reset_otp_fails(user, sms):
    _request_reset(user.mobile_number)
    OTP.objects.filter(user=user).update(expires_at=timezone.now() - timedelta(seconds=1))

    response = _verify_reset(user.mobile_number, sms[0].code)

    assert response.status_code == 400
    assert response.data['errors']['code'] == 'otp_invalid'


def test_reset_otp_attempts_are_capped(user, sms):
    _request_reset(user.mobile_number)
    for _ in range(5):
        _verify_reset(user.mobile_number, _wrong_code(sms[0].code))

    response = _verify_reset(user.mobile_number, sms[0].code)

    assert response.status_code == 400
    assert not PasswordResetToken.objects.exists()


def test_reset_verify_errors_do_not_reveal_accounts(user, sms):
    _request_reset(user.mobile_number)
    known = _verify_reset(user.mobile_number, _wrong_code(sms[0].code))
    unknown = _verify_reset('9999999999', '123456')

    assert known.status_code == unknown.status_code == 400
    assert known.data == unknown.data


def test_registration_otp_cannot_be_used_for_reset(make_user, sms):
    account = make_user()
    generate_and_send_otp(account, OTP.Purpose.REGISTRATION)

    assert _verify_reset(account.mobile_number, sms[0].code).status_code == 400


def test_password_reset_changes_password_without_logging_in(user, sms):
    token = _reset_token_for(user, sms)

    response = _confirm_reset(token)

    assert response.status_code == 200, response.data
    assert response.data['data'] == {}  # no JWTs
    user.refresh_from_db()
    assert user.check_password(NEW_PASSWORD)
    assert not user.check_password(PASSWORD)
    event = _audit(user, AuditEvent.Action.PASSWORD_RESET).get()
    assert event.actor == user
    assert NEW_PASSWORD not in str(event.metadata)
    assert token not in str(event.metadata)


def test_new_password_logs_in_and_old_one_does_not(user, sms):
    _confirm_reset(_reset_token_for(user, sms))

    assert _login(user, PASSWORD).status_code == 401
    assert _login(user, NEW_PASSWORD).status_code == 200


def test_password_reset_revokes_refresh_tokens(user, sms):
    phone = issue_token_pair(user)
    tablet = issue_token_pair(user)

    _confirm_reset(_reset_token_for(user, sms))

    assert _refresh(phone['refresh']).status_code == 401
    assert _refresh(tablet['refresh']).status_code == 401


def test_reset_token_cannot_be_reused(user, sms):
    token = _reset_token_for(user, sms)
    assert _confirm_reset(token).status_code == 200

    again = _confirm_reset(token, password='Another-pass-77')

    assert again.status_code == 400
    assert again.data['errors']['code'] == 'reset_token_invalid'
    user.refresh_from_db()
    assert user.check_password(NEW_PASSWORD)


def test_expired_reset_token_is_refused(user, sms):
    token = _reset_token_for(user, sms)
    PasswordResetToken.objects.update(expires_at=timezone.now() - timedelta(seconds=1))

    response = _confirm_reset(token)

    assert response.status_code == 400
    assert response.data['errors']['code'] == 'reset_token_invalid'
    user.refresh_from_db()
    assert user.check_password(PASSWORD)


def test_newer_reset_token_voids_the_older_one(user, sms, clock):
    first = _reset_token_for(user, sms)
    clock.advance(seconds=150)
    second = _reset_token_for(user, sms)

    assert _confirm_reset(first).status_code == 400
    assert _confirm_reset(second).status_code == 200


def test_password_change_voids_an_outstanding_reset_token(user, sms):
    token = _reset_token_for(user, sms)
    user.set_password('Changed-elsewhere-9')
    user.save(update_fields=['password'])

    response = _confirm_reset(token)

    assert response.status_code == 400
    assert response.data['errors']['code'] == 'reset_token_invalid'


def test_garbage_reset_token_is_refused(user):
    response = _confirm_reset('not-a-real-token')

    assert response.status_code == 400
    assert response.data['errors']['code'] == 'reset_token_invalid'


def test_reset_token_only_changes_its_own_users_password(make_user, sms):
    owner, other = make_user(), make_user()

    _confirm_reset(_reset_token_for(owner, sms))

    other.refresh_from_db()
    assert other.check_password(PASSWORD)


def test_reset_confirm_validates_new_password_and_keeps_token_usable(user, sms):
    token = _reset_token_for(user, sms)

    mismatch = _confirm_reset(token, confirm='Something-else-1')
    weak = _confirm_reset(token, password='12345678')

    assert mismatch.status_code == 400
    assert 'confirm_password' in mismatch.data['errors']
    assert weak.status_code == 400
    assert weak.data['errors']['code'] == 'invalid_new_password'
    assert weak.data['errors']['new_password']
    assert _confirm_reset(token).status_code == 200


def _refresh(raw):
    return _post(REFRESH_URL, {'refresh': raw})
