from django.conf import settings
from django.core.files.storage import FileSystemStorage
from django.utils.functional import cached_property


class PrivateFileSystemStorage(FileSystemStorage):
    """
    Stores files under PRIVATE_MEDIA_ROOT, which is never mapped to a URL.
    Files (payout QR codes, platform QR codes) must be served only through
    an authenticated view that checks ownership/role, so url() is disabled.
    """

    @cached_property
    def base_location(self):
        return self._value_or_setting(self._location, settings.PRIVATE_MEDIA_ROOT)

    def url(self, name):
        raise NotImplementedError(
            'Private files have no public URL; serve them through an authorized view.'
        )


private_storage = PrivateFileSystemStorage()


def get_private_storage():
    return private_storage
