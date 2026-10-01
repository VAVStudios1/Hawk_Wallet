from rest_framework import serializers

from core.admin_serializers import AdminMemberSummarySerializer
from paymethods.serializers import BuyMethodSummarySerializer
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
    # Required by the service (buy_method_required when missing); the id of
    # one of the member's own BUY methods, never a payout method.
    buy_method_id = serializers.IntegerField(min_value=1, required=False, allow_null=True)
    payer_app = serializers.IntegerField(min_value=1, required=False, allow_null=True)


class PayInSerializer(serializers.ModelSerializer):
    order_reference = serializers.CharField(source='order.reference', read_only=True)
    payee_vpa = serializers.CharField(source='payee_vpa_snapshot', read_only=True)
    # PERSONAL / BUY: the member's account the payment came from (null on
    # pay-ins submitted before BUY methods existed).
    buy_method = BuyMethodSummarySerializer(read_only=True, allow_null=True)

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
            'buy_method',
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


class AdminPayInOrderSerializer(serializers.Serializer):
    """The order a pay-in is for, with its stored amounts (never recomputed)."""

    id = serializers.IntegerField()
    reference = serializers.CharField()
    status = serializers.CharField()
    order_amount = serializers.DecimalField(max_digits=12, decimal_places=2)
    payout_target = serializers.DecimalField(max_digits=12, decimal_places=2)


class AdminPayInListSerializer(serializers.ModelSerializer):
    """A pay-in in the admin review list. No BUY-method details: those are detail-only (audited)."""

    order = AdminPayInOrderSerializer(read_only=True)
    member = AdminMemberSummarySerializer(source='user', read_only=True)

    class Meta:
        model = PayIn
        fields = ['id', 'status', 'amount', 'utr', 'submitted_at', 'reviewed_at', 'order', 'member']
        read_only_fields = fields


class AdminPayInDetailSerializer(AdminPayInListSerializer):
    """
    One pay-in for review. payee_vpa is the receiving account the member says
    they paid; buy_method is the member's own account the money came from
    (null on older pay-ins).
    """

    payee_vpa = serializers.CharField(source='payee_vpa_snapshot', read_only=True)
    buy_method = BuyMethodSummarySerializer(read_only=True, allow_null=True)
    reviewed_by_name = serializers.SerializerMethodField()

    class Meta(AdminPayInListSerializer.Meta):
        fields = AdminPayInListSerializer.Meta.fields + [
            'payee_vpa', 'buy_method', 'rejection_reason', 'reviewed_by_name', 'updated_at',
        ]
        read_only_fields = fields

    def get_reviewed_by_name(self, payin) -> str | None:
        return payin.reviewed_by.name if payin.reviewed_by is not None else None
