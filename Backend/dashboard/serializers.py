from rest_framework import serializers


class AdminDashboardSerializer(serializers.Serializer):
    """Shapes an AdminDashboard; every count is computed server-side."""

    total_users = serializers.IntegerField()
    active_orders = serializers.IntegerField()
    pending_payins = serializers.IntegerField()
    pending_payouts = serializers.IntegerField()
    open_support = serializers.IntegerField()
    # Kept nullable for older clients; always an integer now.
    orders_awaiting_action = serializers.IntegerField(allow_null=True)


MONEY = dict(max_digits=16, decimal_places=2)


class CountsByStatusSerializer(serializers.Serializer):
    total = serializers.IntegerField()
    by_status = serializers.DictField(child=serializers.IntegerField())


class PayInReportSerializer(CountsByStatusSerializer):
    confirmed_amount = serializers.DecimalField(**MONEY)


class PayoutReportSerializer(CountsByStatusSerializer):
    paid_amount = serializers.DecimalField(**MONEY)
    pending_amount = serializers.DecimalField(**MONEY)


class MemberReportSerializer(serializers.Serializer):
    new = serializers.IntegerField()
    verified = serializers.IntegerField()
    active = serializers.IntegerField()


class AdminReportSerializer(serializers.Serializer):
    """See dashboard.reports for every figure's basis; all computed server-side."""

    range = serializers.CharField()
    start_date = serializers.DateField()
    end_date = serializers.DateField()
    timezone = serializers.CharField()
    orders = CountsByStatusSerializer()
    pay_ins = PayInReportSerializer()
    payouts = PayoutReportSerializer()
    members = MemberReportSerializer()
    support = CountsByStatusSerializer()


class TotalActiveSerializer(serializers.Serializer):
    total = serializers.IntegerField()
    active = serializers.IntegerField()


class AdminWorkloadRowSerializer(serializers.Serializer):
    id = serializers.IntegerField()
    name = serializers.CharField()
    role = serializers.CharField()
    is_active = serializers.BooleanField()
    open_support_conversations = serializers.IntegerField()
    pending_payouts_created = serializers.IntegerField()
    pay_ins_reviewed = serializers.IntegerField()
    payouts_paid = serializers.IntegerField()


class SystemReportSerializer(serializers.Serializer):
    admins = TotalActiveSerializer()
    members = TotalActiveSerializer()
    active_orders = serializers.IntegerField()
    pending_payins = serializers.IntegerField()
    pending_payouts = serializers.IntegerField()
    open_support = serializers.IntegerField()
    stranded_open_support = serializers.IntegerField()
    workload = AdminWorkloadRowSerializer(many=True)
