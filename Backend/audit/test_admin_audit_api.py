"""API tests for the SUPER_ADMIN audit log: /api/admin/audit-events/ (list, filters, detail)."""
from datetime import timedelta

import pytest
from rest_framework.test import APIClient

from audit.models import AuditEvent
from audit.services import record_audit_event
from core import listing
from core.models import User

URL = '/api/admin/audit-events/'


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


def _event(actor, action=AuditEvent.Action.PROFILE_NAME_CHANGED, target=None, metadata=None):
    return record_audit_event(actor=actor, action=action, target=target or actor, metadata=metadata)


# --- access -------------------------------------------------------------------

def test_only_super_admin_may_read(super_admin, admin_user, make_user):
    event = _event(admin_user)
    for user in (admin_user, make_user()):
        assert _client(user).get(URL).status_code == 403
        assert _client(user).get(f'{URL}{event.pk}/').status_code == 403
    assert APIClient().get(URL).status_code == 401
    assert _data(super_admin)['total'] == 1


# --- list ---------------------------------------------------------------------

def test_list_newest_first_with_actor_and_action_options(super_admin, admin_user):
    first = _event(admin_user)
    second = _event(super_admin, AuditEvent.Action.PASSWORD_CHANGED)

    data = _data(super_admin)
    assert [e['id'] for e in data['events']] == [second.pk, first.pk]
    item = data['events'][1]
    assert item['action'] == 'PROFILE_NAME_CHANGED'
    assert item['action_label'] == 'Profile name changed'
    assert item['actor']['id'] == admin_user.pk
    assert item['actor']['mobile_number_masked'].endswith(admin_user.mobile_number[-4:])
    assert admin_user.mobile_number not in str(item)
    assert (item['target_type'], item['target_id']) == ('core.User', str(admin_user.pk))
    assert 'metadata' not in item
    assert {'value': 'ADMIN_CREATED', 'label': 'Admin created'} in data['actions']


def test_filters(super_admin, admin_user):
    name_change = _event(admin_user)
    password_change = _event(super_admin, AuditEvent.Action.PASSWORD_CHANGED)
    today = listing.reporting_today()

    def ids(**params):
        return [e['id'] for e in _data(super_admin, **params)['events']]

    assert ids(action='PASSWORD_CHANGED') == [password_change.pk]
    assert ids(actor_id=admin_user.pk) == [name_change.pk]
    assert ids(search=admin_user.mobile_number[-6:]) == [name_change.pk]
    assert set(ids(date_from=str(today), date_to=str(today))) == {name_change.pk, password_change.pk}
    assert ids(date_to=str(today - timedelta(days=1))) == []
    assert ids(date_from=str(today + timedelta(days=1))) == []


@pytest.mark.parametrize('params', [
    {'action': 'NOT_AN_ACTION'},
    {'actor_id': 'abc'},
    {'date_from': '01-10-2026'},
    {'date_from': '2026-10-02', 'date_to': '2026-10-01'},
    {'page': 2},
])
def test_invalid_queries_rejected(super_admin, params):
    response = _client(super_admin).get(URL, params)
    assert response.status_code == 400
    assert response.data['errors']['code'] == 'invalid_query'


def test_pagination(super_admin, admin_user):
    for _ in range(3):
        _event(admin_user)
    data = _data(super_admin, page_size=2, page=2)
    assert len(data['events']) == 1
    assert (data['total'], data['has_next']) == (3, False)


# --- detail -------------------------------------------------------------------

def test_detail_shows_metadata_without_sensitive_keys(super_admin, admin_user):
    event = _event(admin_user, metadata={'reason': 'locked', 'password': 'x', 'otp': '123456', 'refresh_token': 't'})
    data = _data(super_admin, f'{URL}{event.pk}/')
    assert data['metadata'] == {'reason': 'locked'}
    assert data['ip_address'] is None
    assert data['action'] == 'PROFILE_NAME_CHANGED'


def test_detail_unknown_is_404(super_admin):
    response = _client(super_admin).get(f'{URL}999999/')
    assert response.status_code == 404
    assert response.data['errors']['code'] == 'audit_event_not_found'


def test_reading_the_log_writes_nothing(super_admin, admin_user):
    event = _event(admin_user)
    _data(super_admin)
    _data(super_admin, f'{URL}{event.pk}/')
    assert AuditEvent.objects.count() == 1
