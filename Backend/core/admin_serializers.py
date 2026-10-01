from rest_framework import serializers

from core.utils import mask_mobile


class AdminMemberSummarySerializer(serializers.Serializer):
    """
    The member a record belongs to, as admin lists and details show them:
    identifier, display name and a masked mobile number (never the full one).
    """

    id = serializers.IntegerField()
    name = serializers.CharField()
    mobile_number_masked = serializers.SerializerMethodField()

    def get_mobile_number_masked(self, user) -> str:
        return mask_mobile(user.mobile_number)


def money(value):
    """A Decimal as the 2-place string every amount field uses."""
    return serializers.DecimalField(max_digits=12, decimal_places=2).to_representation(value)
