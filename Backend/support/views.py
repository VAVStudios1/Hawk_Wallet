import logging

from django.http import FileResponse
from drf_spectacular.types import OpenApiTypes
from drf_spectacular.utils import OpenApiParameter, OpenApiResponse, extend_schema
from rest_framework import status
from rest_framework.parsers import FormParser, MultiPartParser
from rest_framework.throttling import ScopedRateThrottle, UserRateThrottle

from core.api import ServiceAPIView, client_ip
from core.permissions import IsActiveAdmin, IsVerifiedMember
from core.utils import error_response, success_response
from support import services
from support.exceptions import (
    AttachmentNotFound,
    ConversationClosed,
    ConversationNotFound,
    ConversationReadOnly,
    InvalidAttachment,
    InvalidCursor,
    InvalidMessage,
    InvalidPage,
    InvalidStatusFilter,
    SupportConflict,
    SupportError,
    SupportPermissionDenied,
    SupportUnavailable,
)
from support.serializers import (
    AdminConversationSerializer,
    AdminMessageSerializer,
    MarkReadSerializer,
    MemberConversationSerializer,
    MemberMessageSerializer,
    SendAttachmentSerializer,
    SendTextSerializer,
)

logger = logging.getLogger(__name__)

ERROR_STATUS = {
    SupportPermissionDenied: status.HTTP_403_FORBIDDEN,
    ConversationNotFound: status.HTTP_404_NOT_FOUND,
    AttachmentNotFound: status.HTTP_404_NOT_FOUND,
    ConversationClosed: status.HTTP_409_CONFLICT,
    ConversationReadOnly: status.HTTP_403_FORBIDDEN,
    InvalidMessage: status.HTTP_400_BAD_REQUEST,
    InvalidAttachment: status.HTTP_400_BAD_REQUEST,
    InvalidCursor: status.HTTP_400_BAD_REQUEST,
    InvalidPage: status.HTTP_400_BAD_REQUEST,
    InvalidStatusFilter: status.HTTP_400_BAD_REQUEST,
    SupportUnavailable: status.HTTP_503_SERVICE_UNAVAILABLE,
    SupportConflict: status.HTTP_409_CONFLICT,
}

MESSAGE_PARAMETERS = [
    OpenApiParameter('before', OpenApiTypes.UUID, description='Messages older than this one (history).'),
    OpenApiParameter('after', OpenApiTypes.UUID, description='Messages newer than this one (polling).'),
    OpenApiParameter('limit', int, description='1-100 (default 50).'),
]

PAGE_PARAMETERS = [
    OpenApiParameter('page', int, description='1-based page number (default 1).'),
    OpenApiParameter('page_size', int, description='Items per page, 1-100 (default 50).'),
]


class SupportAPIView(ServiceAPIView):
    service_error = SupportError
    error_status = ERROR_STATUS


class MemberSupportAPIView(SupportAPIView):
    """Member-only; always request.user's own conversation, never a client-supplied id."""

    permission_classes = [IsVerifiedMember]


class AdminSupportAPIView(SupportAPIView):
    """Admin-only; the acting admin is always request.user."""

    permission_classes = [IsActiveAdmin]


class AttachmentUploadMixin:
    parser_classes = [MultiPartParser, FormParser]
    # Stricter than the per-user default: every upload is decoded/scanned.
    throttle_classes = [UserRateThrottle, ScopedRateThrottle]
    throttle_scope = 'support_attachment'


def _log_invalid_upload(request, serializer):
    # Field names and validation messages only; never file content or text.
    logger.warning(
        'Support attachment request invalid: fields=%s files=%s errors=%s',
        sorted(request.data.keys()), sorted(request.FILES.keys()), serializer.errors,
    )


def _message_page(page, serializer_class, request):
    return {
        'messages': serializer_class(page.messages, many=True, context={'request': request}).data,
        'has_more': page.has_more,
    }


def _attachment_response(attachment):
    response = FileResponse(
        attachment.file,
        content_type=attachment.content_type,
        as_attachment=True,
        filename=attachment.name,
    )
    # Private chat files must never sit in a shared or browser cache, be
    # sniffed as another type, or run anything if opened in a browser.
    response['Cache-Control'] = 'no-store'
    response['X-Content-Type-Options'] = 'nosniff'
    response['Content-Security-Policy'] = "default-src 'none'; sandbox"
    return response


def _download_schema():
    return extend_schema(responses={200: OpenApiResponse(OpenApiTypes.BINARY, description='Attachment')})


# --- member -------------------------------------------------------------------

class MemberConversationView(MemberSupportAPIView):
    @extend_schema(responses={200: MemberConversationSerializer})
    def get(self, request):
        conversation = services.get_member_conversation(user=request.user)
        data = MemberConversationSerializer(conversation).data if conversation else None
        return success_response('Conversation.', data={'conversation': data})

    @extend_schema(request=None, responses={200: MemberConversationSerializer, 201: MemberConversationSerializer})
    def post(self, request):
        # Returns the OPEN conversation if there is one; never a second.
        conversation, created = services.start_conversation(
            user=request.user, ip_address=client_ip(request)
        )
        return success_response(
            'Conversation started.' if created else 'Conversation.',
            data={'conversation': MemberConversationSerializer(conversation).data},
            status_code=status.HTTP_201_CREATED if created else status.HTTP_200_OK,
        )


class MemberMessagesView(MemberSupportAPIView):
    @extend_schema(parameters=MESSAGE_PARAMETERS, responses={200: MemberMessageSerializer(many=True)})
    def get(self, request):
        page = services.list_member_messages(
            user=request.user,
            before=request.query_params.get('before'),
            after=request.query_params.get('after'),
            limit=request.query_params.get('limit'),
        )
        return success_response('Messages.', data=_message_page(page, MemberMessageSerializer, request))

    @extend_schema(request=SendTextSerializer, responses={201: MemberMessageSerializer})
    def post(self, request):
        serializer = SendTextSerializer(data=request.data)
        if not serializer.is_valid():
            return error_response('Message is invalid.', errors=serializer.errors)
        message = services.send_member_text(user=request.user, text=serializer.validated_data['text'])
        return success_response(
            'Message sent.',
            data=MemberMessageSerializer(message, context={'request': request}).data,
            status_code=status.HTTP_201_CREATED,
        )


class MemberAttachmentUploadView(AttachmentUploadMixin, MemberSupportAPIView):
    @extend_schema(request={'multipart/form-data': SendAttachmentSerializer}, responses={201: MemberMessageSerializer})
    def post(self, request):
        serializer = SendAttachmentSerializer(data=request.data)
        if not serializer.is_valid():
            _log_invalid_upload(request, serializer)
            return error_response('Attachment is invalid.', errors=serializer.errors)
        data = serializer.validated_data
        message = services.send_member_attachment(
            user=request.user,
            message_type=data['type'],
            upload=data['file'],
            caption=data['caption'],
        )
        return success_response(
            'Message sent.',
            data=MemberMessageSerializer(message, context={'request': request}).data,
            status_code=status.HTTP_201_CREATED,
        )


class MemberMarkReadView(MemberSupportAPIView):
    @extend_schema(request=MarkReadSerializer, responses={200: MemberConversationSerializer})
    def post(self, request):
        serializer = MarkReadSerializer(data=request.data)
        if not serializer.is_valid():
            return error_response('Read marker is invalid.', errors=serializer.errors)
        conversation = services.mark_member_read(
            user=request.user, up_to=serializer.validated_data['up_to']
        )
        return success_response(
            'Marked as read.',
            data={'conversation': MemberConversationSerializer(conversation).data},
        )


class MemberAttachmentDownloadView(MemberSupportAPIView):
    @_download_schema()
    def get(self, request, message_id):
        return _attachment_response(
            services.open_member_attachment(user=request.user, message_id=message_id)
        )


# --- admin --------------------------------------------------------------------

class AdminConversationsView(AdminSupportAPIView):
    @extend_schema(
        parameters=[
            OpenApiParameter('status', str, enum=['OPEN', 'CLOSED'], description='Only this status.'),
            *PAGE_PARAMETERS,
        ],
        responses={200: AdminConversationSerializer(many=True)},
    )
    def get(self, request):
        conversations, meta = services.list_admin_conversations(
            admin=request.user,
            status=request.query_params.get('status'),
            page=request.query_params.get('page'),
            page_size=request.query_params.get('page_size'),
        )
        return success_response(
            'Conversations.',
            data={
                'conversations': AdminConversationSerializer(
                    conversations, many=True, context={'request': request}
                ).data,
                **meta,
            },
        )


class AdminConversationDetailView(AdminSupportAPIView):
    @extend_schema(responses={200: AdminConversationSerializer})
    def get(self, request, conversation_id):
        conversation = services.get_admin_conversation(
            admin=request.user, conversation_id=conversation_id, ip_address=client_ip(request)
        )
        return success_response(
            'Conversation.',
            data=AdminConversationSerializer(conversation, context={'request': request}).data,
        )


class AdminMessagesView(AdminSupportAPIView):
    @extend_schema(parameters=MESSAGE_PARAMETERS, responses={200: AdminMessageSerializer(many=True)})
    def get(self, request, conversation_id):
        page = services.list_admin_messages(
            admin=request.user,
            conversation_id=conversation_id,
            before=request.query_params.get('before'),
            after=request.query_params.get('after'),
            limit=request.query_params.get('limit'),
        )
        return success_response('Messages.', data=_message_page(page, AdminMessageSerializer, request))

    @extend_schema(request=SendTextSerializer, responses={201: AdminMessageSerializer})
    def post(self, request, conversation_id):
        serializer = SendTextSerializer(data=request.data)
        if not serializer.is_valid():
            return error_response('Message is invalid.', errors=serializer.errors)
        message = services.send_admin_text(
            admin=request.user,
            conversation_id=conversation_id,
            text=serializer.validated_data['text'],
        )
        return success_response(
            'Message sent.',
            data=AdminMessageSerializer(message, context={'request': request}).data,
            status_code=status.HTTP_201_CREATED,
        )


class AdminAttachmentUploadView(AttachmentUploadMixin, AdminSupportAPIView):
    @extend_schema(request={'multipart/form-data': SendAttachmentSerializer}, responses={201: AdminMessageSerializer})
    def post(self, request, conversation_id):
        serializer = SendAttachmentSerializer(data=request.data)
        if not serializer.is_valid():
            _log_invalid_upload(request, serializer)
            return error_response('Attachment is invalid.', errors=serializer.errors)
        data = serializer.validated_data
        message = services.send_admin_attachment(
            admin=request.user,
            conversation_id=conversation_id,
            message_type=data['type'],
            upload=data['file'],
            caption=data['caption'],
        )
        return success_response(
            'Message sent.',
            data=AdminMessageSerializer(message, context={'request': request}).data,
            status_code=status.HTTP_201_CREATED,
        )


class AdminMarkReadView(AdminSupportAPIView):
    @extend_schema(request=MarkReadSerializer, responses={200: AdminConversationSerializer})
    def post(self, request, conversation_id):
        serializer = MarkReadSerializer(data=request.data)
        if not serializer.is_valid():
            return error_response('Read marker is invalid.', errors=serializer.errors)
        conversation = services.mark_admin_read(
            admin=request.user,
            conversation_id=conversation_id,
            up_to=serializer.validated_data['up_to'],
        )
        return success_response(
            'Marked as read.',
            data=AdminConversationSerializer(conversation, context={'request': request}).data,
        )


class AdminCloseConversationView(AdminSupportAPIView):
    @extend_schema(request=None, responses={200: AdminConversationSerializer})
    def post(self, request, conversation_id):
        conversation = services.close_conversation(
            admin=request.user, conversation_id=conversation_id, ip_address=client_ip(request)
        )
        return success_response(
            'Conversation closed.',
            data=AdminConversationSerializer(conversation, context={'request': request}).data,
        )


class AdminAttachmentDownloadView(AdminSupportAPIView):
    @_download_schema()
    def get(self, request, message_id):
        return _attachment_response(
            services.open_admin_attachment(
                admin=request.user, message_id=message_id, ip_address=client_ip(request)
            )
        )
