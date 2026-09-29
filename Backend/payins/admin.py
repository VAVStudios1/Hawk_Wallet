from django.contrib import admin

from audit.admin import ReadOnlyModelAdmin, ReadOnlyTabularInline
from payins.models import PayIn
from team.admin import CommissionInline


class PayInInline(ReadOnlyTabularInline):
    model = PayIn
    fk_name = 'order'
    fields = ['submitted_at', 'amount', 'utr', 'status', 'buy_method', 'payee_vpa_snapshot', 'reviewed_by', 'reviewed_at']
    ordering = ['submitted_at']


@admin.register(PayIn)
class PayInAdmin(ReadOnlyModelAdmin):
    list_display = ['id', 'order', 'user', 'amount', 'utr', 'status', 'buy_method', 'payee_vpa_snapshot', 'submitted_at', 'reviewed_by', 'reviewed_at']
    list_filter = ['status', 'payer_app', 'submitted_at']
    search_fields = ['utr', 'order__reference', 'user__mobile_number', 'user__name', 'payee_vpa_snapshot', 'buy_method__upi_id', 'idempotency_key']
    list_select_related = ['order', 'user', 'buy_method__provider', 'reviewed_by']
    date_hierarchy = 'submitted_at'
    ordering = ['-submitted_at']
    inlines = [CommissionInline]
