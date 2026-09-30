from django.urls import path

from support.views import (
    AdminAttachmentDownloadView,
    AdminAttachmentUploadView,
    AdminCloseConversationView,
    AdminConversationDetailView,
    AdminConversationsView,
    AdminMarkReadView,
    AdminMessagesView,
)

app_name = 'admin-support'

# Mounted under api/admin/support/; admin-only.
urlpatterns = [
    path('conversations/', AdminConversationsView.as_view(), name='conversations'),
    path('conversations/<uuid:conversation_id>/', AdminConversationDetailView.as_view(), name='conversation-detail'),
    path('conversations/<uuid:conversation_id>/messages/', AdminMessagesView.as_view(), name='messages'),
    path('conversations/<uuid:conversation_id>/attachments/', AdminAttachmentUploadView.as_view(), name='attachments'),
    path('conversations/<uuid:conversation_id>/read/', AdminMarkReadView.as_view(), name='read'),
    path('conversations/<uuid:conversation_id>/close/', AdminCloseConversationView.as_view(), name='close'),
    path('attachments/<uuid:message_id>/', AdminAttachmentDownloadView.as_view(), name='attachment-download'),
]
