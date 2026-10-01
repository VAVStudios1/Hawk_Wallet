from rest_framework import serializers

from core.utils import mask_mobile
from team.models import Commission

MONEY = {'max_digits': 12, 'decimal_places': 2}
RATE = {'max_digits': 6, 'decimal_places': 4}


class ReferralInfoSerializer(serializers.Serializer):
    referral_code = serializers.CharField()
    referral_link = serializers.CharField(allow_null=True)
    direct_team_count = serializers.IntegerField()
    total_team_count = serializers.IntegerField(help_text='Members on every level, however deep.')
    total_commission = serializers.DecimalField(**MONEY)


class TeamLevelSummarySerializer(serializers.Serializer):
    level = serializers.IntegerField()
    commission_eligible = serializers.BooleanField(help_text='False for level 22 and deeper.')
    rate = serializers.DecimalField(
        **RATE, allow_null=True, help_text='Percent, e.g. 0.3000 = 0.3%. Null beyond level 21.'
    )
    member_count = serializers.IntegerField()
    commission_count = serializers.IntegerField()
    commission_total = serializers.DecimalField(**MONEY)


class TeamSummarySerializer(serializers.Serializer):
    direct_team_count = serializers.IntegerField()
    total_team_count = serializers.IntegerField(help_text='Members on every level, however deep.')
    commission_level_team_count = serializers.IntegerField(help_text='Members on levels 1-21.')
    total_commission = serializers.DecimalField(**MONEY)
    commission_count = serializers.IntegerField()
    levels = TeamLevelSummarySerializer(many=True)


class TeamMemberSerializer(serializers.Serializer):
    """Safe team-member view: no id, full mobile number or financial data."""

    level = serializers.SerializerMethodField()
    name = serializers.SerializerMethodField()
    mobile_number_masked = serializers.SerializerMethodField()
    joined_at = serializers.SerializerMethodField()

    # instance is a (level, user) pair from team.services.list_team_members.
    def get_level(self, obj) -> int:
        return obj[0]

    def get_name(self, obj) -> str:
        return obj[1].name

    def get_mobile_number_masked(self, obj) -> str:
        return mask_mobile(obj[1].mobile_number)

    def get_joined_at(self, obj) -> str:
        return serializers.DateTimeField().to_representation(obj[1].date_joined)


class CommissionSerializer(serializers.ModelSerializer):
    source_name = serializers.CharField(source='source_user.name')
    source_mobile_number_masked = serializers.SerializerMethodField()
    order_reference = serializers.CharField(source='payin.order.reference')
    payin_id = serializers.IntegerField()

    class Meta:
        model = Commission
        fields = [
            'id', 'amount', 'level', 'rate', 'base_amount',
            'source_name', 'source_mobile_number_masked',
            'payin_id', 'order_reference', 'created_at',
        ]

    def get_source_mobile_number_masked(self, obj) -> str:
        return mask_mobile(obj.source_user.mobile_number)
