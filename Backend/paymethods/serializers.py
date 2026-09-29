from rest_framework import serializers

from paymethods.models import UserBuyMethod, UserPayoutMethod


class AddPayoutMethodSerializer(serializers.Serializer):
    """
    Input for add_payout_method. Only these fields are read; anything else
    the client sends (user, is_default, is_active, qr_sha256, ...) is ignored.
    At least one of provider / provider_code is required.
    The QR file's content is validated and sanitized by the service.
    """

    # Either field identifies the provider; provider_code (e.g. "PHONEPE")
    # is stable across environments, the numeric id is not.
    provider = serializers.IntegerField(min_value=1, required=False)
    provider_code = serializers.CharField(max_length=32, required=False)
    label = serializers.CharField(max_length=64, required=False, allow_blank=True, default='')
    upi_id = serializers.CharField(max_length=255)
    qr_image = serializers.FileField()

    def validate(self, attrs):
        if 'provider' not in attrs and 'provider_code' not in attrs:
            raise serializers.ValidationError({'provider': ['This field is required.']})
        return attrs


class PayoutMethodSerializer(serializers.ModelSerializer):
    """
    What a member sees of their own payout method. The QR is stored in
    private storage and has no URL, so it is not part of the response.
    """

    provider_code = serializers.CharField(source='provider.code', read_only=True)
    provider_name = serializers.CharField(source='provider.display_name', read_only=True)

    class Meta:
        model = UserPayoutMethod
        fields = [
            'id',
            'provider',
            'provider_code',
            'provider_name',
            'label',
            'upi_id',
            'is_default',
            'is_active',
            'created_at',
            'updated_at',
        ]
        read_only_fields = fields


class PayoutMethodProviderSerializer(serializers.Serializer):
    id = serializers.IntegerField()
    code = serializers.CharField()
    display_name = serializers.CharField()


class PayoutMethodListItemSerializer(serializers.ModelSerializer):
    """
    One saved method in the member's list. Like PayoutMethodSerializer, it
    leaves out the QR (private storage path and hash) and the owner.
    """

    provider = PayoutMethodProviderSerializer(read_only=True)

    class Meta:
        model = UserPayoutMethod
        fields = [
            'id',
            'provider',
            'label',
            'upi_id',
            'is_default',
            'is_active',
            'created_at',
            'updated_at',
        ]
        read_only_fields = fields


class AddBuyMethodSerializer(serializers.Serializer):
    """
    Input for add_buy_method (JSON; a BUY method has no QR). Only these
    fields are read; anything else (user, is_default, is_active, ...) is
    ignored. At least one of provider / provider_code is required.
    """

    provider = serializers.IntegerField(min_value=1, required=False)
    provider_code = serializers.CharField(max_length=32, required=False)
    label = serializers.CharField(max_length=64, required=False, allow_blank=True, default='')
    upi_id = serializers.CharField(max_length=255)

    def validate(self, attrs):
        if 'provider' not in attrs and 'provider_code' not in attrs:
            raise serializers.ValidationError({'provider': ['This field is required.']})
        return attrs


class BuyMethodSerializer(serializers.ModelSerializer):
    """What a member sees of their own buy method (flat provider fields)."""

    provider_code = serializers.CharField(source='provider.code', read_only=True)
    provider_name = serializers.CharField(source='provider.display_name', read_only=True)

    class Meta:
        model = UserBuyMethod
        fields = [
            'id',
            'provider',
            'provider_code',
            'provider_name',
            'label',
            'upi_id',
            'is_default',
            'is_active',
            'created_at',
            'updated_at',
        ]
        read_only_fields = fields


class BuyMethodListItemSerializer(serializers.ModelSerializer):
    """One saved buy method in the member's list; the owner is left out."""

    provider = PayoutMethodProviderSerializer(read_only=True)

    class Meta:
        model = UserBuyMethod
        fields = [
            'id',
            'provider',
            'label',
            'upi_id',
            'is_default',
            'is_active',
            'created_at',
            'updated_at',
        ]
        read_only_fields = fields


class BuyMethodSummarySerializer(serializers.Serializer):
    """The BUY method recorded on a PayIn: which of the member's own accounts paid."""

    id = serializers.IntegerField()
    provider_code = serializers.CharField(source='provider.code')
    provider_name = serializers.CharField(source='provider.display_name')
    label = serializers.CharField()
    upi_id = serializers.CharField()
