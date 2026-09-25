from rest_framework import serializers

from payins.models import PayIn


class PayInIntentSerializer(serializers.Serializer):
    """
    The platform's receiving details for one order. The internal receiving
    account id is not exposed; the signed intent_token carries it.
    """

    order_reference = serializers.CharField()
    amount = serializers.DecimalField(max_digits=12, decimal_places=2)
    currency = serializers.CharField()
    payee_vpa = serializers.CharField()
    payee_name = serializers.CharField()
    upi_uri = serializers.CharField()
    intent_token = serializers.CharField()


class SubmitPayInSerializer(serializers.Serializer):
    """
    Input for submit_pay_in_utr. Only these fields are read; the user,
    order, amount and receiving account never come from the client. Values
    are passed through untouched: the service validates them strictly.
    """

    utr = serializers.CharField(allow_blank=True, trim_whitespace=False)
    idempotency_key = serializers.CharField(allow_blank=True, trim_whitespace=False)
    intent_token = serializers.CharField(allow_blank=True, trim_whitespace=False)
    payer_app = serializers.IntegerField(min_value=1, required=False, allow_null=True)


class PayInSerializer(serializers.ModelSerializer):
    order_reference = serializers.CharField(source='order.reference', read_only=True)
    payee_vpa = serializers.CharField(source='payee_vpa_snapshot', read_only=True)

    class Meta:
        model = PayIn
        fields = [
            'id',
            'order',
            'order_reference',
            'amount',
            'status',
            'utr',
            'payee_vpa',
            'payer_app',
            'rejection_reason',
            'submitted_at',
            'updated_at',
        ]
        read_only_fields = fields


class ReviewPayInSerializer(serializers.Serializer):
    """Input for confirm_pay_in; passed through untouched to the service."""

    idempotency_key = serializers.CharField(allow_blank=True, trim_whitespace=False)


class RejectPayInSerializer(ReviewPayInSerializer):
    """Input for reject_pay_in; the service validates the reason (1-500 chars)."""

    rejection_reason = serializers.CharField(allow_blank=True, trim_whitespace=False)


class AdminPayInSerializer(PayInSerializer):
    """A reviewed PayIn plus the order state the review produced."""

    order_status = serializers.CharField(source='order.status', read_only=True)

    class Meta(PayInSerializer.Meta):
        fields = PayInSerializer.Meta.fields + ['order_status', 'reviewed_by', 'reviewed_at']
        read_only_fields = fields
