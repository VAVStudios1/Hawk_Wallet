from django.contrib import admin
from django.contrib.auth.admin import UserAdmin as DjangoUserAdmin

from core.models import OTP, User


@admin.register(User)
class UserAdmin(DjangoUserAdmin):
    ordering = ['-date_joined']
    list_display = ['mobile_number', 'name', 'is_verified', 'is_active', 'is_staff', 'date_joined']
    list_filter = ['is_verified', 'is_active', 'is_staff']
    search_fields = ['mobile_number', 'name']
    readonly_fields = ['date_joined']

    fieldsets = (
        (None, {'fields': ('mobile_number', 'password')}),
        ('Personal info', {'fields': ('name',)}),
        ('Status', {'fields': ('is_active', 'is_verified', 'is_staff', 'is_superuser', 'groups', 'user_permissions')}),
        ('Important dates', {'fields': ('date_joined', 'last_login')}),
    )
    add_fieldsets = (
        (None, {
            'classes': ('wide',),
            'fields': ('mobile_number', 'name', 'password1', 'password2'),
        }),
    )


@admin.register(OTP)
class OTPAdmin(admin.ModelAdmin):
    list_display = ['user', 'purpose', 'is_used', 'attempts', 'created_at', 'expires_at']
    list_filter = ['purpose', 'is_used']
    search_fields = ['user__mobile_number']
    readonly_fields = ['user', 'purpose', 'code_hash', 'attempts', 'is_used', 'created_at', 'expires_at']

    def has_add_permission(self, request):
        return False
