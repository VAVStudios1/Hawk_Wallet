from django.contrib import admin

from audit.admin import ReadOnlyModelAdmin, ReadOnlyTabularInline
from paymethods.models import PaymentProvider, PlatformReceivingAccount, UserBuyMethod, UserPayoutMethod

# QR images live in private storage with no public URL (url() raises), so
# the admin never renders the file field itself - only whether one exists.


@admin.display(boolean=True, description='QR')
def has_qr(obj):
    return bool(obj.qr_image)


class UserBuyMethodInline(ReadOnlyTabularInline):
    model = UserBuyMethod
    fk_name = 'user'
    fields = ['provider', 'label', 'upi_id', 'is_default', 'is_active', 'created_at']


class UserPayoutMethodInline(ReadOnlyTabularInline):
    model = UserPayoutMethod
    fk_name = 'user'
    fields = ['provider', 'label', 'upi_id', has_qr, 'is_default', 'is_active', 'created_at']


@admin.register(PaymentProvider)
class PaymentProviderAdmin(ReadOnlyModelAdmin):
    list_display = ['code', 'display_name', 'rail', 'enabled_for_buy_methods', 'enabled_for_payout_methods', 'enabled_for_pay_in', 'is_active', 'sort_order']
    list_filter = ['rail', 'is_active', 'enabled_for_buy_methods', 'enabled_for_payout_methods', 'enabled_for_pay_in']
    search_fields = ['code', 'display_name', 'android_package']


@admin.register(UserBuyMethod)
class UserBuyMethodAdmin(ReadOnlyModelAdmin):
    list_display = ['id', 'user', 'provider', 'label', 'upi_id', 'is_default', 'is_active', 'created_at']
    list_filter = ['provider', 'is_active', 'is_default']
    search_fields = ['upi_id', 'label', 'user__mobile_number', 'user__name']
    list_select_related = ['user', 'provider']
    ordering = ['-created_at']


@admin.register(UserPayoutMethod)
class UserPayoutMethodAdmin(ReadOnlyModelAdmin):
    list_display = ['id', 'user', 'provider', 'label', 'upi_id', has_qr, 'is_default', 'is_active', 'created_at']
    list_filter = ['provider', 'is_active', 'is_default']
    search_fields = ['upi_id', 'label', 'qr_sha256', 'user__mobile_number', 'user__name']
    list_select_related = ['user', 'provider']
    exclude = ['qr_image']
    readonly_fields = [has_qr]
    ordering = ['-created_at']


@admin.register(PlatformReceivingAccount)
class PlatformReceivingAccountAdmin(ReadOnlyModelAdmin):
    list_display = ['id', 'payee_name', 'payee_vpa', 'provider', 'merchant_code', has_qr, 'is_active', 'priority', 'created_by', 'created_at']
    list_filter = ['provider', 'is_active']
    search_fields = ['payee_vpa', 'payee_name', 'merchant_code']
    list_select_related = ['provider', 'created_by']
    exclude = ['qr_image']
    readonly_fields = [has_qr]
