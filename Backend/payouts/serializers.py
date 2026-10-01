from rest_framework import serializers

from core.admin_serializers import AdminMemberSummarySerializer, money
from payouts.models import Payout


class CreatePayoutSerializer(serializers.Serializer):
    """
    Input for create_payout. Only amount and key are read; the user, payout
    method and destination always come from the order. The service enforces
    whole rupees and the remaining amount.
    """

    amount = serializers.DecimalField(max_digits=12, decimal_places=2)
    idempotency_key = serializers.CharField(allow_blank=True, trim_whitespace=False)


class MarkPayoutPaidSerializer(serializers.Serializer):
    """Input for mark_payout_paid; the service validates the UTR strictly."""

    utr = serializers.CharField(allow_blank=True, trim_whitespace=False)
    idempotency_key = serializers.CharField(allow_blank=True, trim_whitespace=False)


class CancelPayoutSerializer(serializers.Serializer):
    """Input for cancel_payout; the service validates the reason and key."""

    cancellation_reason = serializers.CharField(allow_blank=True, trim_whitespace=False)
    idempotency_key = serializers.CharField(allow_blank=True, trim_whitespace=False)


class PayoutSerializer(serializers.ModelSerializer):
    """
    A payout and its order's state. The destination (UPI ID, QR) is left
    out: admins read it through the audited payout-method endpoint.
    """

    order_reference = serializers.CharField(source='order.reference', read_only=True)
    order_status = serializers.CharField(source='order.status', read_only=True)
    order_payout_committed_total = serializers.DecimalField(
        source='order.payout_committed_total', max_digits=12, decimal_places=2, read_only=True,
    )
    order_payout_paid_total = serializers.DecimalField(
        source='order.payout_paid_total', max_digits=12, decimal_places=2, read_only=True,
    )

    class Meta:
        model = Payout
        fields = [
            'id',
            'order',
            'order_reference',
            'order_status',
            'order_payout_committed_total',
            'order_payout_paid_total',
            'amount',
            'status',
            'utr',
            'created_by',
            'paid_by',
            'created_at',
            'paid_at',
            'updated_at',
        ]
        read_only_fields = fields


class AdminPayoutMethodSerializer(serializers.Serializer):
    """AdminPayoutMethodView as returned by the service; no QR URL exists."""

    payout_id = serializers.IntegerField()
    order_id = serializers.IntegerField()
    order_reference = serializers.CharField()
    user_id = serializers.IntegerField()
    user_name = serializers.CharField()
    amount = serializers.DecimalField(max_digits=12, decimal_places=2)
    status = serializers.CharField()
    provider_code = serializers.CharField()
    provider_name = serializers.CharField()
    upi_id = serializers.CharField()
    qr_image = serializers.CharField()
    qr_sha256 = serializers.CharField()


class AdminPayoutOrderSerializer(serializers.Serializer):
    """
    The order a payout settles, with its stored totals. remaining_to_commit
    is payout_target - payout_committed_total, the same figure the admin order
    detail shows (what new payouts may still commit).
    """

    id = serializers.IntegerField()
    reference = serializers.CharField()
    status = serializers.CharField()
    payout_target = serializers.DecimalField(max_digits=12, decimal_places=2)
    payout_committed_total = serializers.DecimalField(max_digits=12, decimal_places=2)
    payout_paid_total = serializers.DecimalField(max_digits=12, decimal_places=2)
    remaining_to_commit = serializers.SerializerMethodField()

    def get_remaining_to_commit(self, order) -> str:
        return money(order.payout_target - order.payout_committed_total)


class AdminPayoutListSerializer(serializers.ModelSerializer):
    """A payout in the admin list: provider only, no UPI ID (detail-only, audited)."""

    provider_code = serializers.CharField(source='provider_code_snapshot', read_only=True)
    provider_name = serializers.CharField(source='provider_name_snapshot', read_only=True)
    order = AdminPayoutOrderSerializer(read_only=True)
    member = AdminMemberSummarySerializer(source='user', read_only=True)

    class Meta:
        model = Payout
        fields = ['id', 'status', 'amount', 'provider_code', 'provider_name', 'created_at', 'paid_at', 'order', 'member']
        read_only_fields = fields


class AdminPayoutDetailSerializer(AdminPayoutListSerializer):
    """
    One payout: its destination snapshot (where the money goes), UTR once
    PAID, and the cancellation reason once CANCELLED. The QR is served only by
    the audited payout QR endpoint.
    """

    upi_id = serializers.CharField(source='upi_id_snapshot', read_only=True)
    cancellation_reason = serializers.CharField(source='note', read_only=True)
    created_by_name = serializers.CharField(source='created_by.name', read_only=True)
    paid_by_name = serializers.SerializerMethodField()

    class Meta(AdminPayoutListSerializer.Meta):
        fields = AdminPayoutListSerializer.Meta.fields + [
            'upi_id', 'utr', 'cancellation_reason', 'created_by_name', 'paid_by_name', 'updated_at',
        ]
        read_only_fields = fields

    def get_paid_by_name(self, payout) -> str | None:
        return payout.paid_by.name if payout.paid_by is not None else None
