from django.conf import settings
from django.core.exceptions import ValidationError
from django.db import models
from django.db.models import Q

from appupdates.validators import SHA256_PATTERN, apk_metadata, apk_upload_to, validate_apk_file
from appupdates.versioning import VERSION_MAX_LENGTH, VERSION_PATTERN, AppVersion, InvalidVersion, version_validator
from audit.mixins import ImmutableFieldsMixin, UndeletableModel
from paymethods.storage import get_private_storage


class AppRelease(ImmutableFieldsMixin, UndeletableModel):
    """
    One uploaded Android build. At most one release is published at a time;
    it is the version clients are told to update to, and its
    minimum_supported_version decides which installed versions may still
    use the API. Releases are never deleted, so older ones stay available
    for history and rollback (publish an older release again).

    Change only through appupdates.services, which keeps publishing atomic
    and audited.
    """

    # The build itself never changes once uploaded: a different APK is a
    # new release with a new version.
    immutable_fields = ('version', 'apk_file', 'apk_size', 'apk_sha256', 'created_by')

    version = models.CharField(
        max_length=VERSION_MAX_LENGTH,
        unique=True,
        validators=[version_validator],
        help_text='MAJOR.MINOR.PATCH of this build, e.g. 1.0.7. Must match the APK versionName.',
    )
    minimum_supported_version = models.CharField(
        max_length=VERSION_MAX_LENGTH,
        validators=[version_validator],
        help_text='Installed versions below this must update before using the app. Cannot exceed the version.',
    )
    # Private storage: no public URL and no filesystem path ever reaches a
    # client; the APK is served by appupdates.views.ApkDownloadView.
    apk_file = models.FileField(
        upload_to=apk_upload_to,
        storage=get_private_storage,
        max_length=200,
        validators=[validate_apk_file],
        verbose_name='APK file',
    )
    apk_size = models.PositiveBigIntegerField(editable=False, verbose_name='APK size (bytes)')
    apk_sha256 = models.CharField(max_length=64, editable=False, verbose_name='APK SHA-256')
    release_notes = models.TextField(
        blank=True,
        max_length=5000,
        help_text='One note per line; each line is shown as a separate item.',
    )
    is_published = models.BooleanField(default=False, editable=False)
    published_at = models.DateTimeField(
        null=True, blank=True, editable=False, help_text='When this release was last published.'
    )
    created_by = models.ForeignKey(
        settings.AUTH_USER_MODEL,
        on_delete=models.PROTECT,
        null=True,
        blank=True,
        editable=False,
        related_name='+',
    )
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        ordering = ['-created_at', '-id']
        constraints = [
            models.UniqueConstraint(
                fields=['is_published'],
                condition=Q(is_published=True),
                name='appupdates_one_published_release',
            ),
            models.CheckConstraint(
                condition=Q(version__regex=VERSION_PATTERN),
                name='appupdates_release_version_format',
            ),
            models.CheckConstraint(
                condition=Q(minimum_supported_version__regex=VERSION_PATTERN),
                name='appupdates_release_minimum_version_format',
            ),
            models.CheckConstraint(
                condition=Q(apk_sha256__regex=SHA256_PATTERN),
                name='appupdates_release_sha256_format',
            ),
            models.CheckConstraint(
                condition=Q(apk_size__gt=0),
                name='appupdates_release_apk_size_positive',
            ),
            models.CheckConstraint(
                condition=Q(is_published=False) | Q(published_at__isnull=False),
                name='appupdates_published_release_has_published_at',
            ),
        ]

    def clean(self):
        super().clean()
        try:
            version = AppVersion.parse(self.version)
            minimum = AppVersion.parse(self.minimum_supported_version)
        except InvalidVersion:
            return  # the field validators already report the format
        if minimum > version:
            raise ValidationError(
                {'minimum_supported_version': 'Minimum supported version cannot be greater than the release version.'}
            )

    def save(self, *args, **kwargs):
        # Size and checksum always come from the uploaded content itself.
        if self._state.adding and self.apk_file and not self.apk_sha256:
            self.apk_size, self.apk_sha256 = apk_metadata(self.apk_file)
        super().save(*args, **kwargs)

    def __str__(self):
        return f'Hawk Wallet {self.version}'
