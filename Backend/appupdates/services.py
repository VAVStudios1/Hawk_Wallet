import logging
from dataclasses import dataclass
from urllib.parse import urlsplit

from django.conf import settings
from django.core.exceptions import PermissionDenied, ValidationError
from django.db import IntegrityError, transaction
from django.urls import reverse
from django.utils import timezone

from appupdates.models import AppRelease
from appupdates.versioning import AppVersion, InvalidVersion
from audit.models import AuditEvent
from audit.services import record_audit_event
from core.permissions import is_super_admin

logger = logging.getLogger(__name__)

# Fields whose change on an existing release gets an APP_RELEASE_UPDATED entry.
_AUDITED_FIELDS = ('minimum_supported_version', 'release_notes')


# ---------------------------------------------------------------------------
# Release management (SUPER_ADMIN, via Django Admin)
# ---------------------------------------------------------------------------

def _release_metadata(release, **extra):
    # Identifiers only; a release holds no secrets.
    return {
        'version': release.version,
        'minimum_supported_version': release.minimum_supported_version,
        'apk_sha256': release.apk_sha256,
        **extra,
    }


def save_release(release, *, publish, actor, ip_address=None):
    """
    Create or update `release` and set whether it is the published release.
    Publishing it unpublishes whichever release was published before, in the
    same transaction. The DB allows only one published release, so a
    concurrent publish fails cleanly instead of leaving two.
    """
    if not is_super_admin(actor):
        raise PermissionDenied('Only a SUPER_ADMIN can manage app releases.')

    creating = release._state.adding
    # The APK is immutable, so it is validated once, on upload.
    exclude = ['apk_size', 'apk_sha256'] if creating else ['apk_file', 'apk_size', 'apk_sha256']
    release.full_clean(exclude=exclude, validate_constraints=False)

    try:
        with transaction.atomic():
            stored = None
            changed = []
            if not creating:
                stored = AppRelease.objects.select_for_update().get(pk=release.pk)
                changed = [
                    field for field in _AUDITED_FIELDS
                    if getattr(stored, field) != getattr(release, field)
                ]
            was_published = bool(stored and stored.is_published)

            if publish:
                _unpublish_others(release, actor=actor, ip_address=ip_address)
                if not was_published:
                    release.published_at = timezone.now()
            release.is_published = publish
            if creating:
                release.created_by = actor
            release.save()

            if creating:
                _audit(AuditEvent.Action.APP_RELEASE_CREATED, release, actor, ip_address, apk_size=release.apk_size)
            elif changed:
                _audit(AuditEvent.Action.APP_RELEASE_UPDATED, release, actor, ip_address, changed_fields=changed)
            if publish and not was_published:
                _audit(AuditEvent.Action.APP_RELEASE_PUBLISHED, release, actor, ip_address)
            elif was_published and not publish:
                _audit(AuditEvent.Action.APP_RELEASE_UNPUBLISHED, release, actor, ip_address)
    except IntegrityError:
        if creating:
            # The row was rolled back; let a retry insert it again.
            release.pk = None
            release._state.adding = True
        raise ValidationError('Another release was published at the same time. Reload and try again.')
    return release


def _audit(action, release, actor, ip_address, **extra):
    record_audit_event(
        actor=actor,
        action=action,
        target=release,
        ip_address=ip_address,
        metadata=_release_metadata(release, **extra),
    )


def _unpublish_others(release, *, actor, ip_address):
    others = AppRelease.objects.select_for_update().filter(is_published=True)
    if release.pk is not None:
        others = others.exclude(pk=release.pk)
    for other in others:
        other.is_published = False
        other.save(update_fields=['is_published', 'updated_at'])
        _audit(AuditEvent.Action.APP_RELEASE_UNPUBLISHED, other, actor, ip_address, replaced_by=release.version)


def set_release_published(release, *, publish, actor, ip_address=None):
    """Publish or unpublish a stored release (Django Admin list actions)."""
    release = AppRelease.objects.get(pk=release.pk)
    return save_release(release, publish=publish, actor=actor, ip_address=ip_address)


# ---------------------------------------------------------------------------
# Update policy (update-check API and app-version enforcement)
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class UpdatePolicy:
    """What the published release tells clients. Built only from a usable release."""

    latest_version: AppVersion
    minimum_supported_version: AppVersion
    apk_url: str
    apk_sha256: str
    apk_size: int
    release_notes: tuple


def apk_download_url(release):
    """
    Public HTTPS URL of the release's APK, built from the configured
    APP_RELEASE_DOWNLOAD_BASE_URL (never from the request's Host header).
    None when the setting is missing or not a plain https:// origin.
    """
    base = (settings.APP_RELEASE_DOWNLOAD_BASE_URL or '').strip()
    parts = urlsplit(base)
    if (
        parts.scheme != 'https'
        or not parts.hostname
        or parts.username
        or parts.password
        or parts.query
        or parts.fragment
    ):
        return None
    return base.rstrip('/') + reverse('appupdates:apk-download', kwargs={'version': release.version})


def release_note_lines(text):
    lines = []
    for line in (text or '').splitlines():
        line = line.strip().lstrip('-*•').strip()
        if line:
            lines.append(line)
    return lines


def current_update_policy():
    """
    The published release as an UpdatePolicy, or None when there is no
    usable one (none published, malformed data, missing file, no HTTPS
    download URL). None means "no update known": clients are never forced
    to update to a release they could not download.
    """
    release = AppRelease.objects.filter(is_published=True).first()
    if release is None:
        return None
    try:
        latest = AppVersion.parse(release.version)
        minimum = AppVersion.parse(release.minimum_supported_version)
    except InvalidVersion:
        logger.error('Published app release %s has a malformed version; ignoring it.', release.pk)
        return None
    if minimum > latest:
        logger.error('Published app release %s has minimum > version; ignoring it.', release.pk)
        return None
    if not release.apk_file or not release.apk_sha256 or not release.apk_size:
        logger.error('Published app release %s has no APK metadata; ignoring it.', release.pk)
        return None
    apk_url = apk_download_url(release)
    if apk_url is None:
        logger.error('APP_RELEASE_DOWNLOAD_BASE_URL is not an https:// URL; no app update is offered.')
        return None
    if not release.apk_file.storage.exists(release.apk_file.name):
        logger.error('Published app release %s has no APK file in storage; ignoring it.', release.pk)
        return None
    return UpdatePolicy(
        latest_version=latest,
        minimum_supported_version=minimum,
        apk_url=apk_url,
        apk_sha256=release.apk_sha256,
        apk_size=release.apk_size,
        release_notes=tuple(release_note_lines(release.release_notes)),
    )


def is_version_supported(version, policy):
    """False only when a usable policy exists and `version` is below its minimum."""
    return policy is None or version >= policy.minimum_supported_version


def check_for_update(current_version):
    """The update-check result for an installed `current_version` (AppVersion)."""
    policy = current_update_policy()
    if policy is None:
        return {
            'current_version': str(current_version),
            'latest_version': None,
            'minimum_supported_version': None,
            'supported': True,
            'update_available': False,
            'force_update': False,
            'apk_url': None,
            'apk_sha256': None,
            'apk_size': None,
            'release_notes': [],
        }
    supported = is_version_supported(current_version, policy)
    return {
        'current_version': str(current_version),
        'latest_version': str(policy.latest_version),
        'minimum_supported_version': str(policy.minimum_supported_version),
        'supported': supported,
        'update_available': current_version < policy.latest_version,
        'force_update': not supported,
        'apk_url': policy.apk_url,
        'apk_sha256': policy.apk_sha256,
        'apk_size': policy.apk_size,
        'release_notes': list(policy.release_notes),
    }


def published_release(version):
    """The published release with exactly this version, or None."""
    return AppRelease.objects.filter(is_published=True, version=version).first()
