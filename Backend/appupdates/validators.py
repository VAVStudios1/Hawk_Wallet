import hashlib
import zipfile
from uuid import uuid4

from django.conf import settings
from django.core.exceptions import ValidationError

APK_CONTENT_TYPE = 'application/vnd.android.package-archive'
SHA256_PATTERN = r'^[0-9a-f]{64}$'

# Entries every Hawk Wallet APK has. This only proves the upload is an
# Android package; Android itself checks on install that an update is
# signed with the same key as the installed app.
_REQUIRED_APK_ENTRIES = ('AndroidManifest.xml', 'classes.dex')


def apk_max_bytes():
    return settings.APP_RELEASE_MAX_APK_MB * 1024 * 1024


def validate_apk_file(file):
    """Content check: an .apk extension, a size limit and an APK-shaped ZIP."""
    if not (file.name or '').lower().endswith('.apk'):
        raise ValidationError('Upload an .apk file.')
    if not file.size:
        raise ValidationError('The APK file is empty.')
    if file.size > apk_max_bytes():
        raise ValidationError(f'The APK must be {settings.APP_RELEASE_MAX_APK_MB} MB or smaller.')
    try:
        file.seek(0)
        with zipfile.ZipFile(file) as archive:
            names = set(archive.namelist())
    except (zipfile.BadZipFile, OSError, ValueError):
        raise ValidationError('The file is not a valid APK.')
    finally:
        file.seek(0)
    if not all(entry in names for entry in _REQUIRED_APK_ENTRIES):
        raise ValidationError('The file is not a valid APK.')


def apk_metadata(file):
    """(size in bytes, SHA-256 hex digest) of the file's content."""
    digest = hashlib.sha256()
    size = 0
    file.seek(0)
    for chunk in file.chunks():
        digest.update(chunk)
        size += len(chunk)
    file.seek(0)
    return size, digest.hexdigest()


def apk_upload_to(instance, filename):
    # Never keep the uploaded filename.
    return f'app_releases/{uuid4().hex}.apk'
