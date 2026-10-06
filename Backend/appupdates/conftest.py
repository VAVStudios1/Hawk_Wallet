import zipfile
from io import BytesIO

import pytest
from django.core.files.uploadedfile import SimpleUploadedFile

from appupdates.models import AppRelease
from appupdates.services import save_release
from appupdates.validators import APK_CONTENT_TYPE
from core.models import User
from paymethods.storage import private_storage

DOWNLOAD_BASE_URL = 'https://updates.hawkwallet.example'


def apk_bytes(dex=b'dex-content'):
    buffer = BytesIO()
    with zipfile.ZipFile(buffer, 'w') as archive:
        archive.writestr('AndroidManifest.xml', b'manifest')
        archive.writestr('classes.dex', dex)
    return buffer.getvalue()


def apk_upload(data=None, name='hawk.apk'):
    return SimpleUploadedFile(name, apk_bytes() if data is None else data, content_type=APK_CONTENT_TYPE)


@pytest.fixture(autouse=True)
def private_media_root(settings, tmp_path):
    settings.PRIVATE_MEDIA_ROOT = tmp_path
    settings.APP_RELEASE_DOWNLOAD_BASE_URL = DOWNLOAD_BASE_URL
    for attr in ('base_location', 'location'):
        private_storage.__dict__.pop(attr, None)
    yield tmp_path
    for attr in ('base_location', 'location'):
        private_storage.__dict__.pop(attr, None)


@pytest.fixture
def super_admin(make_user):
    return make_user(role=User.Role.SUPER_ADMIN, is_staff=True)


@pytest.fixture
def make_release(super_admin):
    def _make(version, minimum=None, *, publish=False, notes='', data=None):
        release = AppRelease(
            version=version,
            minimum_supported_version=minimum or version,
            apk_file=apk_upload(data),
            release_notes=notes,
        )
        return save_release(release, publish=publish, actor=super_admin)
    return _make
