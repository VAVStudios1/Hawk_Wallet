from drf_spectacular.utils import extend_schema_field
from rest_framework import serializers

from support.models import MESSAGE_TEXT_MAX_LENGTH, Message

# Members never learn which admin answers them: support messages carry
# this label, never an admin's name, id or role.
SUPPORT_DISPLAY_NAME = 'Hawk Wallet Support'


# --- input --------------------------------------------------------------------

class SendTextSerializer(serializers.Serializer):
    text = serializers.CharField(max_length=MESSAGE_TEXT_MAX_LENGTH)


class SendAttachmentSerializer(serializers.Serializer):
    """
    Multipart input. The declared type only picks which validator runs; the
    stored content type always comes from the server's own checks.
    """

    type = serializers.ChoiceField(choices=[Message.Type.IMAGE, Message.Type.FILE])
    file = serializers.FileField(allow_empty_file=False, use_url=False)
    caption = serializers.CharField(
        max_length=MESSAGE_TEXT_MAX_LENGTH, required=False, allow_blank=True, default=''
    )


class MarkReadSerializer(serializers.Serializer):
    up_to = serializers.UUIDField(help_text='The newest message the client has received.')


# --- output -------------------------------------------------------------------

class AttachmentSerializer(serializers.Serializer):
    """Display metadata only; the file itself is fetched from the download endpoint."""

    name = serializers.CharField(source='attachment_name')
    content_type = serializers.CharField(source='attachment_content_type')
    size = serializers.IntegerField(source='attachment_size')


class _MessageSerializer(serializers.ModelSerializer):
    id = serializers.UUIDField(source='public_id', read_only=True)
    attachment = serializers.SerializerMethodField()

    @extend_schema_field(AttachmentSerializer(allow_null=True))
    def get_attachment(self, message):
        if not message.attachment:
            return None
        return AttachmentSerializer(message).data


class MemberMessageSerializer(_MessageSerializer):
    """A message as the member sees it: no admin identity."""

    from_support = serializers.SerializerMethodField()
    sender_name = serializers.SerializerMethodField()

    class Meta:
        model = Message
        fields = ['id', 'type', 'text', 'attachment', 'from_support', 'sender_name', 'created_at']
        read_only_fields = fields

    def get_from_support(self, message) -> bool:
        return message.sender_id != message.conversation.user_id

    def get_sender_name(self, message) -> str | None:
        return SUPPORT_DISPLAY_NAME if self.get_from_support(message) else None


class AdminMessageSerializer(_MessageSerializer):
    """A message as an admin sees it. Other admins are never identified."""

    from_member = serializers.SerializerMethodField()
    sent_by_me = serializers.SerializerMethodField()

    class Meta:
        model = Message
        fields = ['id', 'type', 'text', 'attachment', 'from_member', 'sent_by_me', 'created_at']
        read_only_fields = fields

    def get_from_member(self, message) -> bool:
        return message.sender_id == message.conversation.user_id

    def get_sent_by_me(self, message) -> bool:
        return message.sender_id == self.context['request'].user.pk


class MemberConversationSerializer(serializers.Serializer):
    """The member's conversation: no assignment or routing information."""

    id = serializers.UUIDField(source='public_id')
    status = serializers.CharField()
    unread_count = serializers.IntegerField()
    last_message_at = serializers.DateTimeField(allow_null=True)
    created_at = serializers.DateTimeField()
    closed_at = serializers.DateTimeField(allow_null=True)


class SupportMemberSerializer(serializers.Serializer):
    """What an admin needs to identify the member they are helping."""

    name = serializers.CharField()
    mobile_number = serializers.CharField()
    member_since = serializers.DateTimeField(source='date_joined')


class AdminConversationSerializer(serializers.Serializer):
    """
    A conversation in the admin inbox. An ADMIN's inbox holds only their
    own conversations, so is_assigned_to_me is always true for them. A
    SUPER_ADMIN sees every conversation, read-only; which admin each one is
    assigned to is not exposed.
    """

    id = serializers.UUIDField(source='public_id')
    status = serializers.CharField()
    member = SupportMemberSerializer(source='user')
    is_assigned_to_me = serializers.SerializerMethodField()
    unread_count = serializers.IntegerField()
    last_message_at = serializers.DateTimeField(allow_null=True)
    created_at = serializers.DateTimeField()
    closed_at = serializers.DateTimeField(allow_null=True)

    def get_is_assigned_to_me(self, conversation) -> bool:
        return conversation.assigned_admin_id == self.context['request'].user.pk
