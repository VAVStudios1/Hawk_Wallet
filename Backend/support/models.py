"""
Support chat between a member and the admin assigned to their conversation.

Chat is a support feature only: nothing here reads or changes orders,
pay-ins, payouts, payment methods, commissions or any balance.
"""
from uuid import uuid4

from django.conf import settings
from django.core.exceptions import ValidationError
from django.db import models
from django.db.models import F, Q
from django.db.models.functions import Length
from django.db.models.lookups import LessThanOrEqual

from audit.mixins import AppendOnlyModel, ImmutableFieldsMixin, UndeletableModel
from paymethods.storage import get_private_storage
from support.attachments import support_attachment_upload_to

MESSAGE_TEXT_MAX_LENGTH = 4000


class Conversation(ImmutableFieldsMixin, UndeletableModel):
    """
    One support thread of a member. The admin is assigned once, when the
    conversation starts (see support.services.start_conversation), and never
    changes; the member never sees or chooses it. A member has at most one
    OPEN conversation; once the assigned admin closes it, the member's next
    conversation is a new row.

    Read state is two watermarks: each side has read every message created
    at or before its *_last_read_at. Messages themselves never change.
    """

    class Status(models.TextChoices):
        OPEN = 'OPEN', 'Open'
        CLOSED = 'CLOSED', 'Closed'

    immutable_fields = ('public_id', 'user', 'assigned_admin', 'created_at')

    # The only identifier the API exposes; the integer pk stays internal.
    public_id = models.UUIDField(default=uuid4, unique=True, editable=False)
    user = models.ForeignKey(
        settings.AUTH_USER_MODEL,
        on_delete=models.PROTECT,
        related_name='support_conversations',
    )
    assigned_admin = models.ForeignKey(
        settings.AUTH_USER_MODEL,
        on_delete=models.PROTECT,
        related_name='assigned_support_conversations',
    )
    status = models.CharField(max_length=10, choices=Status.choices, default=Status.OPEN)

    last_message_at = models.DateTimeField(null=True, blank=True)
    user_last_read_at = models.DateTimeField(null=True, blank=True)
    admin_last_read_at = models.DateTimeField(null=True, blank=True)

    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)
    closed_at = models.DateTimeField(null=True, blank=True)

    class Meta:
        constraints = [
            # One active conversation per member, enforced by the database.
            models.UniqueConstraint(
                fields=['user'],
                condition=Q(status='OPEN'),
                name='support_conversation_one_open_per_user',
            ),
            models.CheckConstraint(
                condition=~Q(assigned_admin=F('user')),
                name='support_conversation_admin_is_not_user',
            ),
            models.CheckConstraint(
                condition=(
                    Q(status='OPEN', closed_at__isnull=True)
                    | Q(status='CLOSED', closed_at__isnull=False)
                ),
                name='support_conversation_closed_at_matches_status',
            ),
        ]
        indexes = [
            # Admin inbox: my conversations by status, most recent activity first.
            models.Index(
                fields=['assigned_admin', 'status', '-last_message_at'],
                name='support_conv_admin_inbox',
            ),
            models.Index(fields=['user', '-created_at'], name='support_conv_user_latest'),
            models.Index(fields=['status', '-last_message_at'], name='support_conv_status_recent'),
        ]

    def __str__(self):
        return f'{self.public_id} ({self.status})'


class Message(AppendOnlyModel):
    """
    One chat message. Append-only: never edited or deleted. Attachments live
    in private storage (no URL) and are read only through the authorized
    download endpoints. attachment_name is a sanitized display name, never a
    path; attachment_content_type is detected by the server.
    """

    class Type(models.TextChoices):
        TEXT = 'TEXT', 'Text'
        IMAGE = 'IMAGE', 'Image'
        FILE = 'FILE', 'File'

    public_id = models.UUIDField(default=uuid4, unique=True, editable=False)
    conversation = models.ForeignKey(
        Conversation,
        on_delete=models.PROTECT,
        related_name='messages',
    )
    sender = models.ForeignKey(
        settings.AUTH_USER_MODEL,
        on_delete=models.PROTECT,
        related_name='+',
    )
    type = models.CharField(max_length=10, choices=Type.choices)
    # The message for TEXT; an optional caption for IMAGE / FILE.
    text = models.TextField(blank=True, default='')

    attachment = models.FileField(
        upload_to=support_attachment_upload_to,
        storage=get_private_storage,
        max_length=255,
        blank=True,
    )
    attachment_name = models.CharField(max_length=100, blank=True)
    attachment_content_type = models.CharField(max_length=50, blank=True)
    attachment_size = models.PositiveIntegerField(null=True, blank=True)
    attachment_sha256 = models.CharField(max_length=64, blank=True)

    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        ordering = ['created_at', 'id']
        constraints = [
            models.CheckConstraint(
                condition=(
                    (
                        Q(type='TEXT')
                        & ~Q(text='')
                        & Q(attachment='')
                        & Q(attachment_size__isnull=True)
                    )
                    | (
                        Q(type__in=['IMAGE', 'FILE'])
                        & ~Q(attachment='')
                        & ~Q(attachment_name='')
                        & ~Q(attachment_content_type='')
                        & Q(attachment_size__gt=0)
                        & ~Q(attachment_sha256='')
                    )
                ),
                name='support_message_type_matches_content',
            ),
            models.CheckConstraint(
                condition=LessThanOrEqual(Length('text'), MESSAGE_TEXT_MAX_LENGTH),
                name='support_message_text_max_length',
            ),
        ]
        indexes = [
            models.Index(fields=['conversation', 'created_at', 'id'], name='support_msg_conv_time'),
        ]

    def save(self, *args, **kwargs):
        # A message can only come from one of the conversation's two
        # participants, whatever path creates it.
        if self._state.adding:
            conversation = self.conversation
            if self.sender_id not in (conversation.user_id, conversation.assigned_admin_id):
                raise ValidationError('Message sender must be a participant of the conversation.')
        super().save(*args, **kwargs)

    def __str__(self):
        return f'{self.type} {self.public_id}'
