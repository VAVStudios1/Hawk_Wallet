from django.urls import path

from support.views import (
    MemberAttachmentDownloadView,
    MemberAttachmentUploadView,
    MemberConversationView,
    MemberMarkReadView,
    MemberMessagesView,
)

app_name = 'support'

# Mounted under api/support/; member-only. There is no conversation id in
# these paths: the member's own conversation is resolved on the server.
urlpatterns = [
    path('conversation/', MemberConversationView.as_view(), name='conversation'),
    path('conversation/messages/', MemberMessagesView.as_view(), name='messages'),
    path('conversation/attachments/', MemberAttachmentUploadView.as_view(), name='attachments'),
    path('conversation/read/', MemberMarkReadView.as_view(), name='read'),
    path('attachments/<uuid:message_id>/', MemberAttachmentDownloadView.as_view(), name='attachment-download'),
]
