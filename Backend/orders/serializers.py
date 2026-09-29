from drf_spectacular.utils import extend_schema_field
from rest_framework import serializers

from orders.calculations import calculate_income_amount, calculate_payout_target
from orders.models import Order, OrderOffer
from orders.services import latest_pay_in
from paymethods.serializers import BuyMethodSummarySerializer
from payouts.models import Payout


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


class OfferPreviewSerializer(serializers.Serializer):
    """
    A claimable offer (orders.services.OfferPreview). income_amount and
    payout_target are computed by the server; quantity is not exposed.
    """

    id = serializers.IntegerField()
    currency = serializers.CharField()
    order_amount = serializers.DecimalField(max_digits=12, decimal_places=2)
    income_rate = serializers.DecimalField(max_digits=5, decimal_places=2)
    income_amount = serializers.DecimalField(max_digits=12, decimal_places=2)
    payout_target = serializers.DecimalField(max_digits=12, decimal_places=2)
    valid_to = serializers.DateTimeField(allow_null=True)


class MemberPayInSummarySerializer(serializers.Serializer):
    """The member's own latest pay-in on an order; no reviewer or account internals."""

    id = serializers.IntegerField()
    status = serializers.CharField()
    amount = serializers.DecimalField(max_digits=12, decimal_places=2)
    utr = serializers.CharField()
    rejection_reason = serializers.CharField()
    # PERSONAL / BUY account the member paid from; null on older pay-ins.
    buy_method = BuyMethodSummarySerializer(allow_null=True)
    submitted_at = serializers.DateTimeField()
    updated_at = serializers.DateTimeField()


class OrderDetailSerializer(OrderSerializer):
    latest_pay_in = serializers.SerializerMethodField()

    class Meta(OrderSerializer.Meta):
        fields = OrderSerializer.Meta.fields + ['latest_pay_in']
        read_only_fields = fields

    @extend_schema_field(MemberPayInSummarySerializer(allow_null=True))
    def get_latest_pay_in(self, order):
        payin = latest_pay_in(order)
        return MemberPayInSummarySerializer(payin).data if payin is not None else None


class CreateOfferSerializer(serializers.Serializer):
    """
    Input for create_offer (multipart/form-data, as it carries the Pay-In
    QR image). Only these fields are read; currency, income, payout target
    and the Pay-In UPI details are never taken from the client. The service
    enforces whole rupees, the rate range and the quantity minimum.
    """

    order_amount = serializers.DecimalField(max_digits=12, decimal_places=2)
    income_rate = serializers.DecimalField(max_digits=5, decimal_places=2)
    quantity_available = serializers.IntegerField(required=False, allow_null=True)
    is_active = serializers.BooleanField()
    # Image of the UPI QR members pay for orders claimed from this offer
    # (multipart). The service validates and decodes it.
    pay_in_qr = serializers.FileField()
    idempotency_key = serializers.CharField(allow_blank=True, trim_whitespace=False)


class AdminOfferSerializer(serializers.ModelSerializer):
    """
    A created offer. income_amount and payout_target are what a claim made
    now would snapshot, computed by orders.calculations - not stored.
    """

    income_amount = serializers.SerializerMethodField()
    payout_target = serializers.SerializerMethodField()

    class Meta:
        model = OrderOffer
        fields = [
            'id',
            'currency',
            'order_amount',
            'income_rate',
            'income_amount',
            'payout_target',
            'quantity_available',
            'is_active',
            'pay_in_upi_id',
            'valid_from',
            'valid_to',
            'created_by',
            'created_at',
        ]
        read_only_fields = fields

    @extend_schema_field(serializers.DecimalField(max_digits=12, decimal_places=2))
    def get_income_amount(self, offer):
        return _money(calculate_income_amount(offer.order_amount, offer.income_rate))

    @extend_schema_field(serializers.DecimalField(max_digits=12, decimal_places=2))
    def get_payout_target(self, offer):
        income = calculate_income_amount(offer.order_amount, offer.income_rate)
        return _money(calculate_payout_target(offer.order_amount, income))


def _money(value):
    return serializers.DecimalField(max_digits=12, decimal_places=2).to_representation(value)


class AdminOrderMemberSerializer(serializers.Serializer):
    """The member who claimed an order: identifier and display name only."""

    id = serializers.IntegerField()
    name = serializers.CharField()


class AdminOrderSerializer(serializers.ModelSerializer):
    """An order in the admin list. No payout destination: that is detail-only (audited)."""

    member = AdminOrderMemberSerializer(source='user', read_only=True)

    class Meta:
        model = Order
        fields = [
            'id',
            'reference',
            'status',
            'currency',
            'member',
            'order_amount',
            'income_rate',
            'income_amount',
            'payout_target',
            'payout_committed_total',
            'payout_paid_total',
            'claimed_at',
            'expires_at',
            'activated_at',
            'completed_at',
            'created_at',
            'updated_at',
        ]
        read_only_fields = fields


class AdminPayInSummarySerializer(serializers.Serializer):
    """An order's latest pay-in as an admin sees it; no receiving-account internals."""

    id = serializers.IntegerField()
    status = serializers.CharField()
    amount = serializers.DecimalField(max_digits=12, decimal_places=2)
    utr = serializers.CharField()
    rejection_reason = serializers.CharField()
    # BUY side: the member's own account the payment came from (null on
    # older pay-ins). Distinct from the order's payout_method (SELL side).
    buy_method = BuyMethodSummarySerializer(allow_null=True)
    submitted_at = serializers.DateTimeField()
    reviewed_at = serializers.DateTimeField(allow_null=True)
    updated_at = serializers.DateTimeField()


class AdminOrderPayoutSerializer(serializers.ModelSerializer):
    """
    One payout in an order's history. The destination is the payout's own
    snapshot (the QR is served only by the audited payout QR endpoint).
    cancellation_reason is set only on CANCELLED payouts.
    """

    provider_code = serializers.CharField(source='provider_code_snapshot', read_only=True)
    provider_name = serializers.CharField(source='provider_name_snapshot', read_only=True)
    upi_id = serializers.CharField(source='upi_id_snapshot', read_only=True)
    cancellation_reason = serializers.CharField(source='note', read_only=True)

    class Meta:
        model = Payout
        fields = [
            'id',
            'amount',
            'status',
            'utr',
            'created_by',
            'paid_by',
            'created_at',
            'paid_at',
            'provider_code',
            'provider_name',
            'upi_id',
            'cancellation_reason',
        ]
        read_only_fields = fields


class AdminOrderDetailSerializer(AdminOrderSerializer):
    """
    pay_in_upi_id is where the member pays the platform (Pay-In); it is
    unrelated to payout_method and payouts, where the platform pays the
    member. remaining_to_commit is what new payouts may still commit:
    payout_target - payout_committed_total (PENDING + PAID).
    """

    pay_in_upi_id = serializers.SerializerMethodField()
    payout_method = OrderPayoutMethodSerializer(read_only=True)
    latest_pay_in = serializers.SerializerMethodField()
    remaining_to_commit = serializers.SerializerMethodField()
    payouts = serializers.SerializerMethodField()

    class Meta(AdminOrderSerializer.Meta):
        fields = AdminOrderSerializer.Meta.fields + [
            'pay_in_upi_id', 'payout_method', 'latest_pay_in', 'remaining_to_commit', 'payouts',
        ]
        read_only_fields = fields

    @extend_schema_field(serializers.CharField(allow_null=True))
    def get_pay_in_upi_id(self, order):
        if order.pay_in_upi_id:
            return order.pay_in_upi_id
        # Orders claimed before offers carried a Pay-In UPI: show where the
        # latest pay-in was actually sent, if any.
        payin = latest_pay_in(order)
        return payin.payee_vpa_snapshot if payin is not None else None

    @extend_schema_field(AdminPayInSummarySerializer(allow_null=True))
    def get_latest_pay_in(self, order):
        payin = latest_pay_in(order)
        return AdminPayInSummarySerializer(payin).data if payin is not None else None

    @extend_schema_field(serializers.DecimalField(max_digits=12, decimal_places=2))
    def get_remaining_to_commit(self, order):
        return _money(order.payout_target - order.payout_committed_total)

    @extend_schema_field(AdminOrderPayoutSerializer(many=True))
    def get_payouts(self, order):
        # Oldest first: the order in which the settlement was built up.
        payouts = order.payouts.order_by('created_at', 'id')
        return AdminOrderPayoutSerializer(payouts, many=True).data
