from django.contrib import admin

from audit.admin import ReadOnlyModelAdmin, ReadOnlyTabularInline
from payouts.models import Payout


class PayoutInline(ReadOnlyTabularInline):
    model = Payout
    fk_name = 'order'
    fields = ['created_at', 'amount', 'status', 'utr', 'upi_id_snapshot', 'provider_name_snapshot', 'created_by', 'paid_by', 'paid_at']
    ordering = ['created_at']


@admin.register(Payout)
class PayoutAdmin(ReadOnlyModelAdmin):
    list_display = ['id', 'order', 'user', 'amount', 'status', 'utr', 'payout_method', 'upi_id_snapshot', 'provider_name_snapshot', 'created_by', 'created_at', 'paid_at']
    list_filter = ['status', 'provider_code_snapshot', 'created_at']
    search_fields = ['utr', 'order__reference', 'user__mobile_number', 'user__name', 'upi_id_snapshot', 'idempotency_key']
    list_select_related = ['order', 'user', 'payout_method__provider', 'created_by']
    date_hierarchy = 'created_at'
    ordering = ['-created_at']
