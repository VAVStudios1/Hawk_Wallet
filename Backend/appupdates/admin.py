from django import forms
from django.contrib import admin, messages
from django.core.exceptions import ValidationError
from django.template.defaultfilters import filesizeformat

from appupdates.models import AppRelease
from appupdates.services import apk_download_url, save_release, set_release_published
from core.api import client_ip
from core.permissions import is_super_admin


class AppReleaseForm(forms.ModelForm):
    # Not the model field: publishing goes through save_release(), which
    # unpublishes the previous release in the same transaction.
    publish = forms.BooleanField(
        required=False,
        label='Published',
        help_text='Make this the active release. The currently published release is unpublished.',
    )

    class Meta:
        model = AppRelease
        fields = ['version', 'minimum_supported_version', 'apk_file', 'release_notes']

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        if self.instance.pk is not None:
            self.fields['publish'].initial = self.instance.is_published


@admin.register(AppRelease)
class AppReleaseAdmin(admin.ModelAdmin):
    """
    SUPER_ADMIN-only release management. Releases are never deleted; the
    APK and version of a saved release cannot change.
    """

    form = AppReleaseForm
    list_display = ['version', 'minimum_supported_version', 'is_published', 'apk_size_display', 'created_at', 'published_at']
    list_filter = ['is_published', 'created_at', 'published_at']
    search_fields = ['version', 'minimum_supported_version', 'apk_sha256', 'release_notes']
    ordering = ['-created_at', '-id']
    date_hierarchy = 'created_at'
    actions = ['publish_selected', 'unpublish_selected']

    _add_fieldsets = (
        ('Release', {'fields': ('version', 'minimum_supported_version', 'apk_file', 'release_notes', 'publish')}),
    )
    _change_fieldsets = (
        ('Release', {'fields': ('version', 'minimum_supported_version', 'release_notes', 'publish')}),
        ('APK', {'fields': ('apk_file_name', 'apk_size_display', 'apk_sha256', 'download_url')}),
        ('History', {'fields': ('is_published', 'published_at', 'created_by', 'created_at', 'updated_at')}),
    )

    # --- access: SUPER_ADMIN only, never delete ---------------------------

    def has_module_permission(self, request):
        return is_super_admin(request.user)

    def has_view_permission(self, request, obj=None):
        return is_super_admin(request.user)

    def has_add_permission(self, request):
        return is_super_admin(request.user)

    def has_change_permission(self, request, obj=None):
        return is_super_admin(request.user)

    def has_delete_permission(self, request, obj=None):
        return False

    # --- form layout ------------------------------------------------------

    def get_fieldsets(self, request, obj=None):
        return self._change_fieldsets if obj else self._add_fieldsets

    def get_readonly_fields(self, request, obj=None):
        if obj is None:
            return []
        return [
            'version', 'apk_file_name', 'apk_size_display', 'apk_sha256', 'download_url',
            'is_published', 'published_at', 'created_by', 'created_at', 'updated_at',
        ]

    @admin.display(description='APK size', ordering='apk_size')
    def apk_size_display(self, obj):
        return f'{filesizeformat(obj.apk_size)} ({obj.apk_size:,} bytes)' if obj.apk_size else '-'

    @admin.display(description='APK file')
    def apk_file_name(self, obj):
        # Private storage has no URL; show the stored name only.
        return obj.apk_file.name or '-'

    @admin.display(description='Public download URL')
    def download_url(self, obj):
        if not obj.is_published:
            return 'Only the published release is downloadable.'
        return apk_download_url(obj) or 'Not available: APP_RELEASE_DOWNLOAD_BASE_URL is not an https:// URL.'

    # --- saving through the service --------------------------------------

    def save_model(self, request, obj, form, change):
        save_release(
            obj,
            publish=form.cleaned_data.get('publish', False),
            actor=request.user,
            ip_address=client_ip(request),
        )

    # --- list actions -----------------------------------------------------

    @admin.action(description='Publish selected release')
    def publish_selected(self, request, queryset):
        if queryset.count() != 1:
            self.message_user(request, 'Select exactly one release to publish.', messages.ERROR)
            return
        self._set_published(request, queryset.get(), publish=True)

    @admin.action(description='Unpublish selected releases')
    def unpublish_selected(self, request, queryset):
        for release in queryset.filter(is_published=True):
            self._set_published(request, release, publish=False)

    def _set_published(self, request, release, *, publish):
        try:
            set_release_published(release, publish=publish, actor=request.user, ip_address=client_ip(request))
        except ValidationError as exc:
            self.message_user(request, f'{release.version}: {" ".join(exc.messages)}', messages.ERROR)
            return
        state = 'published' if publish else 'unpublished'
        self.message_user(request, f'Release {release.version} {state}.', messages.SUCCESS)
