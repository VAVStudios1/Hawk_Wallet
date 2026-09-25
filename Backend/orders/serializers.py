from rest_framework import serializers

from orders.models import Order


class ClaimOrderSerializer(serializers.Serializer):
    """
    Input for claim_order. Only these fields are read; amounts, rates,
    status and the user always come from the server. idempotency_key is
    passed through untouched and validated by the service.
    """

    offer_id = serializers.IntegerField(min_value=1)
    idempotency_key = serializers.CharField(allow_blank=True, trim_whitespace=False)
    payout_method_id = serializers.IntegerField(min_value=1, required=False, allow_null=True)


class OrderPayoutMethodSerializer(serializers.Serializer):
    """The member's own payout method fixed on the order (no QR internals)."""

    id = serializers.IntegerField()
    provider_code = serializers.CharField(source='provider.code')
    provider_name = serializers.CharField(source='provider.display_name')
    label = serializers.CharField()
    upi_id = serializers.CharField()


class OrderSerializer(serializers.ModelSerializer):
    payout_method = OrderPayoutMethodSerializer(read_only=True)

    class Meta:
        model = Order
        fields = [
            'id',
            'reference',
            'status',
            'currency',
            'order_amount',
            'income_rate',
            'income_amount',
            'payout_target',
            'payout_method',
            'claimed_at',
            'expires_at',
            'activated_at',
            'completed_at',
            'created_at',
            'updated_at',
        ]
        read_only_fields = fields
