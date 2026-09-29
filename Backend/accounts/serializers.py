from rest_framework import serializers

from core.models import User


class ProfileSerializer(serializers.ModelSerializer):
    """The user's own account details. No balances or other money figures."""

    class Meta:
        model = User
        fields = [
            'id',
            'name',
            'mobile_number',
            'role',
            'is_verified',
            'date_joined',
            'referral_code',
        ]
        read_only_fields = fields


class UpdateProfileSerializer(serializers.Serializer):
    # Name is the only editable field; any other key in the body is ignored.
    name = serializers.CharField(max_length=150)

    def validate_name(self, value):
        value = value.strip()
        if not value:
            raise serializers.ValidationError('Name is required.')
        return value


class ChangePasswordSerializer(serializers.Serializer):
    current_password = serializers.CharField(write_only=True, trim_whitespace=False)
    new_password = serializers.CharField(write_only=True, trim_whitespace=False)
    confirm_password = serializers.CharField(write_only=True, trim_whitespace=False)

    def validate(self, attrs):
        if attrs['new_password'] != attrs['confirm_password']:
            raise serializers.ValidationError({'confirm_password': 'Passwords do not match.'})
        return attrs
