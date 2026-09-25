from rest_framework import serializers

from paymethods.models import UserPayoutMethod


class AddPayoutMethodSerializer(serializers.Serializer):
    """
    Input for add_payout_method. Only these fields are read; anything else
    the client sends (user, is_default, is_active, qr_sha256, ...) is ignored.
    The QR file's content is validated and sanitized by the service.
    """

    provider = serializers.IntegerField(min_value=1)
    label = serializers.CharField(max_length=64, required=False, allow_blank=True, default='')
    upi_id = serializers.CharField(max_length=255)
    qr_image = serializers.FileField()


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
