from django.contrib.auth.password_validation import validate_password
from rest_framework import serializers

from core.models import User, mobile_number_validator
from core.utils import mask_mobile
from orders.models import Order


class AdminUserSerializer(serializers.ModelSerializer):
    """A member account as admins see it: masked mobile, no secrets."""

    mobile_number_masked = serializers.SerializerMethodField()

    class Meta:
        model = User
        fields = ['id', 'name', 'mobile_number_masked', 'role', 'is_verified', 'is_active', 'date_joined']
        read_only_fields = fields

    def get_mobile_number_masked(self, user) -> str:
        return mask_mobile(user.mobile_number)


class AdminUserOrderSerializer(serializers.ModelSerializer):
    """One of the member's recent orders, with its stored amounts."""

    class Meta:
        model = Order
        fields = ['id', 'reference', 'status', 'order_amount', 'income_amount', 'payout_target', 'claimed_at']
        read_only_fields = fields


class AdminUserDetailSerializer(serializers.Serializer):
    """Shapes an AdminUserDetail; every count is computed server-side."""

    user = AdminUserSerializer()
    orders_total = serializers.IntegerField()
    order_counts = serializers.DictField(child=serializers.IntegerField())
    pay_in_counts = serializers.DictField(child=serializers.IntegerField())
    recent_orders = AdminUserOrderSerializer(many=True)


class AdminWorkloadSerializer(serializers.Serializer):
    """Counts the data attributes to one admin (see accounts.admin_management)."""

    open_support_conversations = serializers.IntegerField()
    pending_payouts_created = serializers.IntegerField()
    pay_ins_reviewed = serializers.IntegerField()
    payouts_paid = serializers.IntegerField()


class AdminAccountSerializer(AdminUserSerializer):
    """
    An ADMIN / SUPER_ADMIN account for a SUPER_ADMIN: masked mobile, no
    secrets, its workload, and whether the caller may change its status
    (ADMIN accounts other than the caller's own; the backend enforces it).
    """

    workload = serializers.SerializerMethodField()
    is_self = serializers.SerializerMethodField()
    can_change_status = serializers.SerializerMethodField()

    class Meta(AdminUserSerializer.Meta):
        fields = AdminUserSerializer.Meta.fields + ['workload', 'is_self', 'can_change_status']
        read_only_fields = fields

    def get_workload(self, account) -> dict:
        return AdminWorkloadSerializer(account).data

    def _viewer_id(self):
        request = self.context.get('request')
        return request.user.pk if request is not None else None

    def get_is_self(self, account) -> bool:
        return account.pk == self._viewer_id()

    def get_can_change_status(self, account) -> bool:
        return account.role == User.Role.ADMIN and account.pk != self._viewer_id()


class CreateAdminSerializer(serializers.Serializer):
    """
    Input for create_admin: the same name, mobile and password rules as
    member registration (RegisterSerializer). No role field: the account is
    always ADMIN. No referral code: admins have no referrer.
    """

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
