from rest_framework import serializers

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
