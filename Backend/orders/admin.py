from django.contrib import admin

from audit.admin import LedgerEntryInline, ReadOnlyModelAdmin, ReadOnlyTabularInline
from orders.models import Order, OrderOffer
from payins.admin import PayInInline
from payouts.admin import PayoutInline


class OrderInline(ReadOnlyTabularInline):
    model = Order
    fk_name = 'user'
    fields = ['reference', 'status', 'order_amount', 'income_amount', 'payout_target', 'payout_paid_total', 'payout_method', 'claimed_at']
    ordering = ['-claimed_at']


@admin.register(OrderOffer)
class OrderOfferAdmin(ReadOnlyModelAdmin):
    list_display = ['id', 'currency', 'order_amount', 'income_rate', 'is_active', 'quantity_available', 'pay_in_upi_id', 'valid_from', 'valid_to', 'created_by', 'created_at']
    list_filter = ['is_active', 'currency']
    search_fields = ['pay_in_upi_id', 'idempotency_key', 'created_by__mobile_number', 'created_by__name']
    list_select_related = ['created_by']
    ordering = ['-created_at']


@admin.register(Order)
class OrderAdmin(ReadOnlyModelAdmin):
    list_display = ['reference', 'user', 'status', 'order_amount', 'income_rate', 'income_amount', 'payout_target', 'payout_committed_total', 'payout_paid_total', 'payout_method', 'claimed_at']
    list_filter = ['status', 'currency', 'claimed_at']
    search_fields = ['reference', 'user__mobile_number', 'user__name', 'pay_in_upi_id', 'payout_method__upi_id', 'idempotency_key']
    list_select_related = ['user', 'payout_method__provider']
    date_hierarchy = 'claimed_at'
    ordering = ['-claimed_at']
    inlines = [PayInInline, PayoutInline, LedgerEntryInline]
