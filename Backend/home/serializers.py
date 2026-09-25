from rest_framework import serializers

MONEY = dict(max_digits=16, decimal_places=2)


class HomeTransactionSerializer(serializers.Serializer):
    type = serializers.CharField()
    title = serializers.CharField()
    amount = serializers.DecimalField(**MONEY)
    status = serializers.CharField()
    created_at = serializers.DateTimeField()
    reference = serializers.CharField()


class MemberHomeSerializer(serializers.Serializer):
    """Shapes a MemberHome for the app; every value is computed server-side."""

    user = serializers.SerializerMethodField()
    balance = serializers.SerializerMethodField()
    summary = serializers.SerializerMethodField()
    deposit_withdrawal = serializers.SerializerMethodField()
    recent_transactions = HomeTransactionSerializer(many=True)

    def _money(self, value):
        return serializers.DecimalField(**MONEY).to_representation(value)

    def get_user(self, home):
        return {'id': home.user_id, 'name': home.user_name}

    def get_balance(self, home):
        return {'available': self._money(home.available), 'pending': self._money(home.pending)}

    def get_summary(self, home):
        return {
            'total_earnings': self._money(home.total_earnings),
            'active_orders': home.active_orders,
            'pending_payouts': self._money(home.pending),
        }

    def get_deposit_withdrawal(self, home):
        return {'deposit': self._money(home.deposit), 'withdrawal': self._money(home.withdrawal)}
