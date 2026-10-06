import hashlib
import zipfile
from io import BytesIO

import pytest
from django.core.exceptions import PermissionDenied, ValidationError
from django.db import IntegrityError, transaction

from appupdates.conftest import DOWNLOAD_BASE_URL, apk_bytes, apk_upload
from appupdates.models import AppRelease
from appupdates.services import (
    apk_download_url,
    current_update_policy,
    save_release,
    set_release_published,
)
from audit.mixins import AppendOnlyError, ImmutableFieldError
from audit.models import AuditEvent
from core.models import User


# --- validation ------------------------------------------------------------

def test_minimum_cannot_exceed_version(make_release):
    with pytest.raises(ValidationError) as exc:
        make_release('1.0.9', '1.0.10')
    assert 'minimum_supported_version' in exc.value.message_dict
    assert not AppRelease.objects.exists()


def test_minimum_equal_to_version_is_allowed(make_release):
    assert make_release('1.0.10', '1.0.10').minimum_supported_version == '1.0.10'


def test_minimum_below_version_compares_numerically(make_release):
    # As strings '1.0.9' > '1.0.10'; numerically it is lower, so this is valid.
    assert make_release('1.0.10', '1.0.9').pk is not None


@pytest.mark.parametrize('version', ['1.0', '1.0.0-beta', 'v1.0.0', '01.0.0'])
def test_malformed_release_version_is_rejected(make_release, version):
    with pytest.raises(ValidationError):
        make_release(version, '1.0.0')


def test_duplicate_version_is_rejected(make_release):
    make_release('1.0.7')
    with pytest.raises(ValidationError):
        make_release('1.0.7')


@pytest.mark.parametrize('data,name', [
    (b'not a zip at all', 'hawk.apk'),
    (b'', 'hawk.apk'),
    (None, 'hawk.zip'),
])
def test_non_apk_upload_is_rejected(super_admin, data, name):
    release = AppRelease(version='1.0.7', minimum_supported_version='1.0.7', apk_file=apk_upload(data, name))
    with pytest.raises(ValidationError) as exc:
        save_release(release, publish=False, actor=super_admin)
    assert 'apk_file' in exc.value.message_dict


def test_zip_without_android_entries_is_rejected(super_admin):
    buffer = BytesIO()
    with zipfile.ZipFile(buffer, 'w') as archive:
        archive.writestr('readme.txt', b'hello')
    release = AppRelease(version='1.0.7', minimum_supported_version='1.0.7', apk_file=apk_upload(buffer.getvalue()))
    with pytest.raises(ValidationError):
        save_release(release, publish=False, actor=super_admin)


def test_apk_over_size_limit_is_rejected(super_admin, settings):
    settings.APP_RELEASE_MAX_APK_MB = 0
    release = AppRelease(version='1.0.7', minimum_supported_version='1.0.7', apk_file=apk_upload())
    with pytest.raises(ValidationError):
        save_release(release, publish=False, actor=super_admin)


# --- metadata --------------------------------------------------------------

def test_size_and_sha256_come_from_the_apk_content(make_release, private_media_root):
    data = apk_bytes(b'unique build bytes')
    release = make_release('1.0.7', data=data)
    assert release.apk_size == len(data)
    assert release.apk_sha256 == hashlib.sha256(data).hexdigest()
    # Stored under a random name in private storage, not the uploaded name.
    assert release.apk_file.name.startswith('app_releases/')
    assert 'hawk' not in release.apk_file.name
    assert (private_media_root / release.apk_file.name).read_bytes() == data


def test_apk_and_version_are_immutable(make_release):
    release = make_release('1.0.7')
    release.version = '1.0.8'
    with pytest.raises(ImmutableFieldError):
        release.save()
    release.refresh_from_db()
    release.apk_sha256 = '0' * 64
    with pytest.raises(ImmutableFieldError):
        release.save()


def test_releases_cannot_be_deleted(make_release):
    release = make_release('1.0.7')
    with pytest.raises(AppendOnlyError):
        release.delete()
    with pytest.raises(AppendOnlyError):
        AppRelease.objects.all().delete()


# --- publishing ------------------------------------------------------------

def test_publishing_a_new_release_unpublishes_the_previous_one(make_release, super_admin):
    old = make_release('1.0.6', publish=True)
    new = make_release('1.0.7', '1.0.6', publish=True)
    old.refresh_from_db()
    assert not old.is_published
    assert new.is_published and new.published_at is not None
    assert list(AppRelease.objects.filter(is_published=True)) == [new]
    # History is kept.
    assert AppRelease.objects.count() == 2
    actions = list(
        AuditEvent.objects.filter(target_type='appupdates.AppRelease').values_list('action', 'target_id', 'actor')
    )
    assert (AuditEvent.Action.APP_RELEASE_UNPUBLISHED, str(old.pk), super_admin.pk) in actions
    assert (AuditEvent.Action.APP_RELEASE_PUBLISHED, str(new.pk), super_admin.pk) in actions


def test_rollback_republishes_an_older_release(make_release, super_admin):
    old = make_release('1.0.6', publish=True)
    new = make_release('1.0.7', publish=True)
    set_release_published(old, publish=True, actor=super_admin)
    old.refresh_from_db()
    new.refresh_from_db()
    assert old.is_published and not new.is_published


def test_unpublish_leaves_no_active_release(make_release, super_admin):
    release = make_release('1.0.7', publish=True)
    set_release_published(release, publish=False, actor=super_admin)
    assert not AppRelease.objects.filter(is_published=True).exists()
    assert current_update_policy() is None


def test_database_allows_only_one_published_release(make_release):
    make_release('1.0.6', publish=True)
    other = make_release('1.0.7')
    with pytest.raises(IntegrityError), transaction.atomic():
        AppRelease.objects.filter(pk=other.pk).update(is_published=True, published_at=other.created_at)


@pytest.mark.parametrize('role', [User.Role.MEMBER, User.Role.ADMIN])
def test_only_super_admin_can_save_releases(make_user, role):
    actor = make_user(role=role, is_staff=True)
    release = AppRelease(version='1.0.7', minimum_supported_version='1.0.7', apk_file=apk_upload())
    with pytest.raises(PermissionDenied):
        save_release(release, publish=True, actor=actor)
    assert not AppRelease.objects.exists()


def test_create_and_update_are_audited(make_release, super_admin):
    release = make_release('1.0.7', '1.0.5')
    release.minimum_supported_version = '1.0.6'
    save_release(release, publish=False, actor=super_admin)
    events = AuditEvent.objects.filter(target_id=str(release.pk), target_type='appupdates.AppRelease')
    assert set(events.values_list('action', flat=True)) == {
        AuditEvent.Action.APP_RELEASE_CREATED,
        AuditEvent.Action.APP_RELEASE_UPDATED,
    }
    updated = events.get(action=AuditEvent.Action.APP_RELEASE_UPDATED)
    assert updated.metadata['changed_fields'] == ['minimum_supported_version']


# --- update policy / download URL -------------------------------------------

def test_active_release_is_the_published_one(make_release):
    make_release('1.0.8')  # newer but unpublished: never offered
    make_release('1.0.7', '1.0.6', publish=True, notes='- Bug fixes\n\n* Security improvements\n')
    policy = current_update_policy()
    assert str(policy.latest_version) == '1.0.7'
    assert str(policy.minimum_supported_version) == '1.0.6'
    assert policy.release_notes == ('Bug fixes', 'Security improvements')


def test_no_policy_without_published_release(make_release):
    assert current_update_policy() is None
    make_release('1.0.7')
    assert current_update_policy() is None


def test_download_url_is_https_and_has_no_storage_path(make_release):
    release = make_release('1.0.7', publish=True)
    url = apk_download_url(release)
    assert url == f'{DOWNLOAD_BASE_URL}/api/app/releases/1.0.7/apk/'
    assert release.apk_file.name not in url


@pytest.mark.parametrize('base', [
    '',
    'http://updates.hawkwallet.example',
    'ftp://updates.hawkwallet.example',
    'https://',
    'https://user:pass@updates.hawkwallet.example',
    'https://updates.hawkwallet.example/?next=http://evil.example',
    'updates.hawkwallet.example',
])
def test_non_https_base_url_offers_no_update(make_release, settings, base):
    settings.APP_RELEASE_DOWNLOAD_BASE_URL = base
    release = make_release('1.0.7', publish=True)
    assert apk_download_url(release) is None
    assert current_update_policy() is None


def test_malformed_stored_release_is_ignored(make_release):
    release = make_release('1.0.7', publish=True)
    assert current_update_policy() is not None
    # minimum > version cannot pass save_release(); simulate bad data written
    # around it (e.g. a manual DB edit).
    AppRelease.objects.filter(pk=release.pk).update(version='1.0.5', minimum_supported_version='1.0.6')
    assert current_update_policy() is None


def test_missing_apk_file_offers_no_update(make_release, private_media_root):
    release = make_release('1.0.7', publish=True)
    (private_media_root / release.apk_file.name).unlink()
    assert current_update_policy() is None
