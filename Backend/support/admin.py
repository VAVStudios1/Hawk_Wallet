from django.contrib import admin

from audit.admin import ReadOnlyModelAdmin, ReadOnlyTabularInline
from support.models import Conversation, Message

# View-only: chat is changed only through the services, which enforce the
# routing, participant and lifecycle rules. The attachment file itself is
# never linked (private storage has no URL); only its metadata is shown.
MESSAGE_FIELDS = [
    'created_at',
    'sender',
    'type',
    'text',
    'attachment_name',
    'attachment_content_type',
    'attachment_size',
    'attachment_sha256',
]


class MessageInline(ReadOnlyTabularInline):
    model = Message
    fields = MESSAGE_FIELDS
    ordering = ['created_at', 'id']


@admin.register(Conversation)
class ConversationAdmin(ReadOnlyModelAdmin):
    list_display = ['public_id', 'user', 'assigned_admin', 'status', 'last_message_at', 'created_at', 'closed_at']
    list_filter = ['status', 'created_at']
    search_fields = [
        'public_id',
        'user__mobile_number',
        'user__name',
        'assigned_admin__mobile_number',
        'assigned_admin__name',
    ]
    list_select_related = ['user', 'assigned_admin']
    date_hierarchy = 'created_at'
    ordering = ['-created_at', '-id']
    inlines = [MessageInline]


@admin.register(Message)
class MessageAdmin(ReadOnlyModelAdmin):
    list_display = ['public_id', 'created_at', 'conversation', 'sender', 'type', 'attachment_name']
    list_filter = ['type', 'created_at']
    search_fields = ['public_id', 'conversation__public_id', 'sender__mobile_number', 'sender__name']
    list_select_related = ['conversation', 'sender']
    date_hierarchy = 'created_at'
    ordering = ['-created_at', '-id']
    fields = ['public_id', 'conversation', *MESSAGE_FIELDS]
