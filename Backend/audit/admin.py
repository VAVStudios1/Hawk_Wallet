from django.contrib import admin

from audit.models import AuditEvent, LedgerEntry


class ReadOnlyAdminMixin:
    """
    View-only admin for inspecting data. Nothing can be added, changed or
    deleted here: financial and configuration changes must go through the
    services, which enforce invariants and write audit/ledger entries.
    """

    def has_add_permission(self, request, obj=None):
        return False

    def has_change_permission(self, request, obj=None):
        return False

    def has_delete_permission(self, request, obj=None):
        return False


class ReadOnlyModelAdmin(ReadOnlyAdminMixin, admin.ModelAdmin):
    pass


class ReadOnlyTabularInline(ReadOnlyAdminMixin, admin.TabularInline):
    extra = 0
    show_change_link = True

    def get_readonly_fields(self, request, obj=None):
        return self.fields

class LedgerEntryInline(ReadOnlyTabularInline):
    model = LedgerEntry
    fk_name = 'order'
    fields = ['created_at', 'action', 'amount', 'balance_before', 'balance_after', 'payin', 'payout', 'commission', 'actor', 'reference_id']
    ordering = ['created_at', 'id']


@admin.register(LedgerEntry)
class LedgerEntryAdmin(ReadOnlyModelAdmin):
    list_display = ['id', 'created_at', 'action', 'order', 'amount', 'balance_before', 'balance_after', 'payin', 'payout', 'commission', 'actor', 'reference_id']
    list_filter = ['action', 'created_at']
    search_fields = ['order__reference', 'reference_id', 'actor__mobile_number', 'actor__name', 'order__user__mobile_number', 'payin__utr', 'payout__utr', 'commission__beneficiary__mobile_number']
    list_select_related = ['order', 'payin', 'payout', 'commission', 'actor']
    date_hierarchy = 'created_at'
    ordering = ['-created_at', '-id']


@admin.register(AuditEvent)
class AuditEventAdmin(ReadOnlyModelAdmin):
    list_display = ['id', 'created_at', 'action', 'actor', 'target_type', 'target_id', 'ip_address']
    list_filter = ['action', 'target_type', 'created_at']
    search_fields = ['target_id', 'target_type', 'actor__mobile_number', 'actor__name', 'ip_address']
    list_select_related = ['actor']
    date_hierarchy = 'created_at'
    ordering = ['-created_at', '-id']
