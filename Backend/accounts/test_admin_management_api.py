"""API tests for SUPER_ADMIN admin management: /api/admin/admins/ (list, create, detail, activate, deactivate)."""
from decimal import Decimal

import pytest
from rest_framework.test import APIClient
from rest_framework_simplejwt.token_blacklist.models import OutstandingToken

from audit.models import AuditEvent
from conftest import buy_method_for, next_key
from core.models import User
from core.tokens import issue_token_pair
from payins.services import confirm_pay_in, get_pay_in_intent, submit_pay_in_utr
from payouts.services import create_payout
from support.models import Conversation
from support.services import close_conversation

URL = '/api/admin/admins/'
_utrs = iter(range(500000000000, 999999999999))
_mobiles = iter(range(7000000000, 7099999999))

STRONG_PASSWORD = 'Harbour-Lantern-2048'


@pytest.fixture
def super_admin(make_user):
    return make_user(role=User.Role.SUPER_ADMIN)


def _client(user):
    client = APIClient()
    client.force_authenticate(user)
    return client


def _data(user, url=URL, **params):
    response = _client(user).get(url, params)
    assert response.status_code == 200, response.data
    return response.data['data']


def _new_admin_body(**overrides):
    body = {
        'name': 'Neha Admin',
        'mobile_number': str(next(_mobiles)),
        'password': STRONG_PASSWORD,
        'confirm_password': STRONG_PASSWORD,
    }
    body.update(overrides)
    return body


# --- access -------------------------------------------------------------------

def test_admin_and_member_forbidden_anonymous_rejected(admin_user, make_user):
    member = make_user()
    for user in (admin_user, member):
        client = _client(user)
        assert client.get(URL).status_code == 403
        assert client.post(URL, _new_admin_body(), format='json').status_code == 403
        assert client.get(f'{URL}{admin_user.pk}/').status_code == 403
        assert client.post(f'{URL}{admin_user.pk}/deactivate/').status_code == 403
    assert APIClient().get(URL).status_code == 401
    assert User.objects.filter(role=User.Role.ADMIN).count() == 1


def test_inactive_super_admin_forbidden(make_user):
    assert _client(make_user(role=User.Role.SUPER_ADMIN, is_active=False)).get(URL).status_code == 403


# --- list & detail ------------------------------------------------------------

def test_lists_admin_accounts_only(super_admin, admin_user, make_user):
    make_user()  # a member
    data = _data(super_admin)
    assert {a['id'] for a in data['admins']} == {super_admin.pk, admin_user.pk}
    item = next(a for a in data['admins'] if a['id'] == admin_user.pk)
    assert item['mobile_number_masked'] == '******' + admin_user.mobile_number[-4:]
    assert admin_user.mobile_number not in str(item)
    assert item['workload'] == {
        'open_support_conversations': 0, 'pending_payouts_created': 0, 'pay_ins_reviewed': 0, 'payouts_paid': 0,
    }
    assert item['is_self'] is False and item['can_change_status'] is True
    me = next(a for a in data['admins'] if a['id'] == super_admin.pk)
    assert me['is_self'] is True and me['can_change_status'] is False


def test_filters_search_and_pagination(super_admin, make_user):
    inactive = make_user(role=User.Role.ADMIN, is_active=False)
    active = make_user(role=User.Role.ADMIN)
    assert {a['id'] for a in _data(super_admin, is_active='false')['admins']} == {inactive.pk}
    assert active.pk in {a['id'] for a in _data(super_admin, is_active='true')['admins']}
    assert [a['id'] for a in _data(super_admin, search=inactive.mobile_number[-5:])['admins']] == [inactive.pk]
    page = _data(super_admin, page_size=2)
    assert (page['total'], page['has_next']) == (3, True)
    assert _client(super_admin).get(URL, {'is_active': 'maybe'}).status_code == 400


def test_workload_counts_what_is_attributable(super_admin, admin_user, make_user, make_method, make_order, receiving_account):
    member = make_user()
    make_method(member, 'w@ybl', is_default=True)
    Conversation.objects.create(user=member, assigned_admin=admin_user)
    order = make_order(member)
    intent = get_pay_in_intent(user=member, order_id=order.pk)
    payin = submit_pay_in_utr(
        user=member, order_id=order.pk, utr=str(next(_utrs)), idempotency_key=next_key(),
        intent_token=intent.intent_token, buy_method_id=buy_method_for(member).pk,
    )
    confirm_pay_in(admin=admin_user, payin_id=payin.pk, idempotency_key=next_key())
    create_payout(admin=admin_user, order_id=order.pk, amount=Decimal('50'), idempotency_key=next_key())

    workload = _data(super_admin, f'{URL}{admin_user.pk}/')['workload']
    assert workload == {
        'open_support_conversations': 1, 'pending_payouts_created': 1, 'pay_ins_reviewed': 1, 'payouts_paid': 0,
    }


def test_detail_unknown_or_member_is_404(super_admin, make_user):
    for pk in (make_user().pk, 999999):
        response = _client(super_admin).get(f'{URL}{pk}/')
        assert response.status_code == 404
        assert response.data['errors']['code'] == 'admin_not_found'


# --- create -------------------------------------------------------------------

def test_super_admin_creates_unverified_admin_and_audits(super_admin):
    body = _new_admin_body(role='SUPER_ADMIN', is_verified=True)  # extra keys are ignored
    response = _client(super_admin).post(URL, body, format='json')
    assert response.status_code == 201, response.data

    account = User.objects.get(mobile_number=body['mobile_number'])
    assert account.role == User.Role.ADMIN
    assert account.is_verified is False  # proves the mobile with an OTP on first login
    assert account.is_active is True and account.is_staff is False
    assert account.check_password(STRONG_PASSWORD)
    assert response.data['data']['id'] == account.pk
    assert 'password' not in str(response.data)

    event = AuditEvent.objects.get(action=AuditEvent.Action.ADMIN_CREATED)
    assert event.actor == super_admin
    assert (event.target_type, event.target_id) == ('core.User', str(account.pk))
    assert event.metadata == {'role': 'ADMIN'}


@pytest.mark.parametrize('overrides, field', [
    ({'confirm_password': 'Different-Password-1'}, 'confirm_password'),
    ({'password': '123', 'confirm_password': '123'}, 'password'),
    ({'mobile_number': '12345'}, 'mobile_number'),
    ({'mobile_number': '5123456789'}, 'mobile_number'),
    ({'name': '   '}, 'name'),
])
def test_create_validates_like_registration(super_admin, overrides, field):
    response = _client(super_admin).post(URL, _new_admin_body(**overrides), format='json')
    assert response.status_code == 400
    assert field in response.data['errors']
    assert AuditEvent.objects.filter(action=AuditEvent.Action.ADMIN_CREATED).count() == 0


def test_create_rejects_existing_mobile(super_admin, make_user):
    member = make_user()
    response = _client(super_admin).post(URL, _new_admin_body(mobile_number=member.mobile_number), format='json')
    assert response.status_code == 400
    assert 'mobile_number' in response.data['errors']
    member.refresh_from_db()
    assert member.role == User.Role.MEMBER


# --- activate / deactivate ----------------------------------------------------

def test_deactivate_keeps_history_revokes_sessions_and_audits(super_admin, admin_user, make_user):
    conversation = Conversation.objects.create(user=make_user(), assigned_admin=admin_user)
    issue_token_pair(admin_user)

    response = _client(super_admin).post(f'{URL}{admin_user.pk}/deactivate/')
    assert response.status_code == 200, response.data
    assert response.data['data']['is_active'] is False

    admin_user.refresh_from_db()
    assert admin_user.is_active is False
    conversation.refresh_from_db()
    assert conversation.assigned_admin_id == admin_user.pk  # no eligible ADMIN to take it
    live = OutstandingToken.objects.filter(user=admin_user, blacklistedtoken__isnull=True)
    assert live.count() == 0
    event = AuditEvent.objects.get(action=AuditEvent.Action.ADMIN_DEACTIVATED)
    assert event.actor == super_admin
    # No other ADMIN exists, so the open chat stays (stranded) rather than going to a SUPER_ADMIN.
    assert event.metadata == {
        'role': 'ADMIN', 'revoked_refresh_tokens': 1, 'support_reassigned': 0, 'support_stranded': 1,
    }

    # The deactivated admin can no longer use admin endpoints.
    assert _client(admin_user).get('/api/admin/orders/').status_code == 403

    # Repeating is a no-op without a second event; activating is audited.
    assert _client(super_admin).post(f'{URL}{admin_user.pk}/deactivate/').status_code == 200
    assert AuditEvent.objects.filter(action=AuditEvent.Action.ADMIN_DEACTIVATED).count() == 1
    assert _client(super_admin).post(f'{URL}{admin_user.pk}/activate/').status_code == 200
    admin_user.refresh_from_db()
    assert admin_user.is_active is True
    assert AuditEvent.objects.get(action=AuditEvent.Action.ADMIN_ACTIVATED).actor == super_admin


def test_super_admin_cannot_deactivate_self(super_admin):
    response = _client(super_admin).post(f'{URL}{super_admin.pk}/deactivate/')
    assert response.status_code == 409
    assert response.data['errors']['code'] == 'cannot_manage_own_account'
    super_admin.refresh_from_db()
    assert super_admin.is_active is True


def test_other_super_admins_are_not_manageable(super_admin, make_user):
    other = make_user(role=User.Role.SUPER_ADMIN)
    response = _client(super_admin).post(f'{URL}{other.pk}/deactivate/')
    assert response.status_code == 409
    assert response.data['errors']['code'] == 'admin_not_manageable'
    other.refresh_from_db()
    assert other.is_active is True
    assert AuditEvent.objects.count() == 0


def test_members_cannot_be_changed_here(super_admin, make_user):
    member = make_user()
    assert _client(super_admin).post(f'{URL}{member.pk}/deactivate/').status_code == 404
    member.refresh_from_db()
    assert member.is_active is True


# --- deactivation: open support conversations -------------------------------

def test_deactivation_reassigns_open_chats_by_routing_and_audits(super_admin, admin_user, make_user):
    busy = make_user(role=User.Role.ADMIN)
    idle = make_user(role=User.Role.ADMIN)
    Conversation.objects.create(user=make_user(), assigned_admin=busy)       # busy already has 1 open
    first = Conversation.objects.create(user=make_user(), assigned_admin=admin_user)
    second = Conversation.objects.create(user=make_user(), assigned_admin=admin_user)
    closed = Conversation.objects.create(user=make_user(), assigned_admin=admin_user)
    close_conversation(admin=admin_user, conversation_id=closed.public_id)

    response = _client(super_admin).post(f'{URL}{admin_user.pk}/deactivate/')
    assert response.status_code == 200, response.data
    assert response.data['data']['workload']['open_support_conversations'] == 0

    first.refresh_from_db()
    second.refresh_from_db()
    closed.refresh_from_db()
    # Least-loaded routing, re-evaluated per conversation: idle (0) gets the
    # first, then busy and idle tie at 1 and the lower id wins.
    assert first.assigned_admin_id == idle.pk
    assert second.assigned_admin_id == min(busy.pk, idle.pk)
    assert closed.assigned_admin_id == admin_user.pk  # CLOSED untouched
    assert super_admin.pk not in {first.assigned_admin_id, second.assigned_admin_id}

    events = AuditEvent.objects.filter(action=AuditEvent.Action.SUPPORT_CONVERSATION_REASSIGNED).order_by('id')
    assert [e.target_id for e in events] == [str(first.pk), str(second.pk)]
    assert all(e.actor == super_admin for e in events)
    assert events[0].metadata == {
        'conversation_id': str(first.public_id), 'from_admin_id': admin_user.pk,
        'to_admin_id': idle.pk, 'reason': 'admin_deactivated',
    }
    deactivated = AuditEvent.objects.get(action=AuditEvent.Action.ADMIN_DEACTIVATED)
    assert deactivated.metadata['support_reassigned'] == 2
    assert deactivated.metadata['support_stranded'] == 0


def test_without_another_admin_chats_stay_and_show_as_stranded(super_admin, admin_user, make_user):
    conversation = Conversation.objects.create(user=make_user(), assigned_admin=admin_user)

    response = _client(super_admin).post(f'{URL}{admin_user.pk}/deactivate/')
    assert response.status_code == 200
    # Never handed to a SUPER_ADMIN; it stays put and is reported.
    conversation.refresh_from_db()
    assert conversation.assigned_admin_id == admin_user.pk
    assert response.data['data']['workload']['open_support_conversations'] == 1
    assert AuditEvent.objects.get(action=AuditEvent.Action.ADMIN_DEACTIVATED).metadata['support_stranded'] == 1
    assert not AuditEvent.objects.filter(action=AuditEvent.Action.SUPPORT_CONVERSATION_REASSIGNED).exists()
    assert _data(super_admin, '/api/admin/system-report/')['stranded_open_support'] == 1


def test_reassigned_chat_is_usable_by_the_new_admin_only(super_admin, admin_user, make_user):
    other = make_user(role=User.Role.ADMIN)
    conversation = Conversation.objects.create(user=make_user(), assigned_admin=admin_user)
    _client(super_admin).post(f'{URL}{admin_user.pk}/deactivate/')

    path = f'/api/admin/support/conversations/{conversation.public_id}/'
    assert _client(other).get(path).status_code == 200
    assert _client(other).post(f'{path}messages/', {'text': 'Hello, taking over.'}, format='json').status_code == 201
    # SUPER_ADMIN still sees it and is still read-only.
    assert _client(super_admin).get(path).status_code == 200
    assert _client(super_admin).post(f'{path}messages/', {'text': 'x'}, format='json').status_code in (403, 409)
