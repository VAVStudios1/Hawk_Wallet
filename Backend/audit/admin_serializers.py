from rest_framework import serializers

from audit.models import AuditEvent
from core.utils import mask_mobile

# metadata never holds secrets by contract (see AuditEvent); these keys are
# dropped anyway, so a mistake elsewhere can never surface here.
SENSITIVE_KEY_PARTS = ('password', 'otp', 'token', 'secret', 'hash', 'upi', 'qr')


def safe_metadata(metadata):
    if not isinstance(metadata, dict):
        return {}
    return {
        key: value
        for key, value in metadata.items()
        if not any(part in str(key).lower() for part in SENSITIVE_KEY_PARTS)
    }


class AuditActorSerializer(serializers.Serializer):
    id = serializers.IntegerField()
    name = serializers.CharField()
    role = serializers.CharField()
    mobile_number_masked = serializers.SerializerMethodField()

    def get_mobile_number_masked(self, user) -> str:
        return mask_mobile(user.mobile_number)


class AuditEventSerializer(serializers.ModelSerializer):
    """One audit event in the log; actor is null for system actions."""

    action_label = serializers.CharField(source='get_action_display', read_only=True)
    actor = AuditActorSerializer(read_only=True, allow_null=True)

    class Meta:
        model = AuditEvent
        fields = ['id', 'action', 'action_label', 'actor', 'target_type', 'target_id', 'created_at']
        read_only_fields = fields


class AuditEventDetailSerializer(AuditEventSerializer):
    """The full event: adds the request IP and the identifier-only metadata."""

    metadata = serializers.SerializerMethodField()

    class Meta(AuditEventSerializer.Meta):
        fields = AuditEventSerializer.Meta.fields + ['ip_address', 'metadata']
        read_only_fields = fields

    def get_metadata(self, event) -> dict:
        return safe_metadata(event.metadata)
