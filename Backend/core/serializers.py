from django.contrib.auth.password_validation import validate_password
from rest_framework import serializers

from core.models import OTP, User, mobile_number_validator


class RegisterSerializer(serializers.Serializer):
    name = serializers.CharField(max_length=150)
    mobile_number = serializers.CharField(max_length=10, validators=[mobile_number_validator])
    password = serializers.CharField(write_only=True)
    confirm_password = serializers.CharField(write_only=True)

    def validate_name(self, value):
        value = value.strip()
        if not value:
            raise serializers.ValidationError('Name is required.')
        return value

    def validate_mobile_number(self, value):
        if User.objects.filter(mobile_number=value).exists():
            raise serializers.ValidationError('An account with this mobile number already exists.')
        return value

    def validate_password(self, value):
        validate_password(value)
        return value

    def validate(self, attrs):
        if attrs['password'] != attrs['confirm_password']:
            raise serializers.ValidationError({'confirm_password': 'Passwords do not match.'})
        return attrs

    def create(self, validated_data):
        return User.objects.create_user(
            mobile_number=validated_data['mobile_number'],
            name=validated_data['name'],
            password=validated_data['password'],
        )


class LoginSerializer(serializers.Serializer):
    mobile_number = serializers.CharField(max_length=10)
    password = serializers.CharField(write_only=True)


class VerifyOTPSerializer(serializers.Serializer):
    mobile_number = serializers.CharField(max_length=10)
    otp = serializers.CharField(max_length=10, min_length=4)


class ResendOTPSerializer(serializers.Serializer):
    mobile_number = serializers.CharField(max_length=10)
    purpose = serializers.ChoiceField(choices=OTP.Purpose.choices)
