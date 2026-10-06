"""Django Admin release management: SUPER_ADMIN only."""
import pytest

from appupdates.conftest import apk_upload
from appupdates.models import AppRelease
from audit.models import AuditEvent
from core.models import User

CHANGELIST = '/admin/appupdates/apprelease/'
ADD = CHANGELIST + 'add/'


def _form(**overrides):
    data = {
        'version': '1.0.7',
        'minimum_supported_version': '1.0.6',
        'release_notes': 'Bug fixes',
        'apk_file': apk_upload(),
        'publish': 'on',
        '_save': 'Save',
    }
    data.update(overrides)
    return data


@pytest.fixture
def admin_client_for(client):
    def _login(user):
        client.force_login(user)
        return client
    return _login


def test_super_admin_lists_and_views_releases(admin_client_for, super_admin, make_release):
    old = make_release('1.0.6', publish=True)
    make_release('1.0.7', publish=True)
    client = admin_client_for(super_admin)
    response = client.get(CHANGELIST)
    assert response.status_code == 200
    assert b'1.0.6' in response.content and b'1.0.7' in response.content
    detail = client.get(f'{CHANGELIST}{old.pk}/change/')
    assert detail.status_code == 200
    assert old.apk_sha256.encode() in detail.content


def test_super_admin_uploads_and_publishes_release(admin_client_for, super_admin, make_release):
    previous = make_release('1.0.6', publish=True)
    response = admin_client_for(super_admin).post(ADD, _form())
    assert response.status_code == 302
    release = AppRelease.objects.get(version='1.0.7')
    assert release.is_published and release.created_by == super_admin
    assert len(release.apk_sha256) == 64 and release.apk_size > 0
    previous.refresh_from_db()
    assert not previous.is_published
    assert AuditEvent.objects.filter(
        action=AuditEvent.Action.APP_RELEASE_PUBLISHED, target_id=str(release.pk), actor=super_admin
    ).exists()


def test_admin_form_rejects_minimum_above_version(admin_client_for, super_admin):
    response = admin_client_for(super_admin).post(ADD, _form(minimum_supported_version='1.0.10', version='1.0.9'))
    assert response.status_code == 200
    assert not AppRelease.objects.exists()


def test_admin_form_rejects_non_apk(admin_client_for, super_admin):
    response = admin_client_for(super_admin).post(ADD, _form(apk_file=apk_upload(b'plain text')))
    assert response.status_code == 200
    assert not AppRelease.objects.exists()


def test_publish_action(admin_client_for, super_admin, make_release):
    first = make_release('1.0.6', publish=True)
    second = make_release('1.0.7')
    response = admin_client_for(super_admin).post(
        CHANGELIST, {'action': 'publish_selected', '_selected_action': [second.pk]}
    )
    assert response.status_code == 302
    first.refresh_from_db()
    second.refresh_from_db()
    assert second.is_published and not first.is_published


def test_super_admin_cannot_delete(admin_client_for, super_admin, make_release):
    release = make_release('1.0.7')
    response = admin_client_for(super_admin).post(f'{CHANGELIST}{release.pk}/delete/', {'post': 'yes'})
    assert response.status_code == 403
    assert AppRelease.objects.filter(pk=release.pk).exists()


@pytest.mark.parametrize('extra', [
    {'role': User.Role.ADMIN, 'is_staff': True},
    # A Django superuser flag alone does not grant access; the role does.
    {'role': User.Role.ADMIN, 'is_staff': True, 'is_superuser': True},
])
def test_android_admins_cannot_manage_releases(admin_client_for, make_user, make_release, extra):
    release = make_release('1.0.7')
    client = admin_client_for(make_user(**extra))
    assert client.get(CHANGELIST).status_code == 403
    assert client.get(f'{CHANGELIST}{release.pk}/change/').status_code == 403
    assert client.post(ADD, _form(version='1.0.8')).status_code == 403
    assert not AppRelease.objects.filter(version='1.0.8').exists()


def test_members_and_anonymous_cannot_reach_releases(admin_client_for, client, make_user):
    assert client.get(CHANGELIST).status_code == 302  # to admin login
    member_client = admin_client_for(make_user(role=User.Role.MEMBER))
    assert member_client.get(CHANGELIST).status_code == 302
    assert member_client.post(ADD, _form()).status_code == 302
    assert not AppRelease.objects.exists()


def test_inactive_super_admin_is_denied(admin_client_for, make_user):
    inactive = make_user(role=User.Role.SUPER_ADMIN, is_staff=True, is_active=False)
    response = admin_client_for(inactive).get(CHANGELIST)
    assert response.status_code in (302, 403)
