from django.contrib import admin
from django.contrib.auth.admin import UserAdmin as DjangoUserAdmin

from audit.admin import ReadOnlyTabularInline
from core.models import OTP, User
from orders.admin import OrderInline
from paymethods.admin import UserBuyMethodInline, UserPayoutMethodInline


class HasReferrerFilter(admin.SimpleListFilter):
    title = 'referrer'
    parameter_name = 'has_referrer'

    def lookups(self, request, model_admin):
        return [('yes', 'Referred'), ('no', 'Not referred')]

    def queryset(self, request, queryset):
        if self.value() == 'yes':
            return queryset.filter(referrer__isnull=False)
        if self.value() == 'no':
            return queryset.filter(referrer__isnull=True)
        return queryset


class DirectReferralInline(ReadOnlyTabularInline):
    """The user's level-1 team (users who registered with their code)."""

    model = User
    fk_name = 'referrer'
    verbose_name = 'direct referral'
    verbose_name_plural = 'direct referrals'
    fields = ['mobile_number', 'name', 'referral_code', 'is_verified', 'is_active', 'date_joined']
    ordering = ['date_joined']


@admin.register(User)
class UserAdmin(DjangoUserAdmin):
    ordering = ['-date_joined']
    list_display = ['mobile_number', 'name', 'role', 'referral_code', 'referrer', 'is_verified', 'is_active', 'is_staff', 'date_joined']
    list_filter = ['role', 'is_verified', 'is_active', 'is_staff', HasReferrerFilter]
    search_fields = ['mobile_number', 'name', 'referral_code', 'referrer__mobile_number', 'referrer__referral_code']
    list_select_related = ['referrer']
    # role is shown but not editable here: role changes are privileged and
    # must be audited, which this form does not do. Referral code and
    # referrer are fixed at registration and never editable.
    readonly_fields = ['role', 'date_joined', 'referral_code', 'referrer']
    inlines = [UserBuyMethodInline, UserPayoutMethodInline, OrderInline, DirectReferralInline]

    fieldsets = (
        (None, {'fields': ('mobile_number', 'password')}),
        ('Personal info', {'fields': ('name', 'role')}),
        ('Team', {'fields': ('referral_code', 'referrer')}),
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
