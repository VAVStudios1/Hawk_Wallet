from drf_spectacular.utils import OpenApiParameter, extend_schema
from rest_framework import status

from audit.admin_serializers import AuditEventDetailSerializer, AuditEventSerializer
from audit.admin_services import get_audit_event, list_audit_events
from audit.exceptions import AuditEventNotFound, AuditLogError, AuditLogPermissionDenied, InvalidAuditQuery
from audit.models import AuditEvent
from core.api import ServiceAPIView
from core.permissions import IsSuperAdmin
from core.utils import success_response


class AuditLogAPIView(ServiceAPIView):
    """SUPER_ADMIN-only and read-only."""

    permission_classes = [IsSuperAdmin]
    service_error = AuditLogError
    error_status = {
        AuditLogPermissionDenied: status.HTTP_403_FORBIDDEN,
        AuditEventNotFound: status.HTTP_404_NOT_FOUND,
        InvalidAuditQuery: status.HTTP_400_BAD_REQUEST,
    }


class AuditEventsView(AuditLogAPIView):
    @extend_schema(
        parameters=[
            OpenApiParameter('action', str, enum=list(AuditEvent.Action.values)),
            OpenApiParameter('actor_id', int),
            OpenApiParameter('date_from', str, description='YYYY-MM-DD, inclusive.'),
            OpenApiParameter('date_to', str, description='YYYY-MM-DD, inclusive.'),
            OpenApiParameter('search', str, description='Actor name or target type; digits match mobile / target id.'),
            OpenApiParameter('page', int, description='1-based page number.'),
            OpenApiParameter('page_size', int, description='1-100; default 20.'),
        ],
        responses={200: AuditEventSerializer(many=True)},
    )
    def get(self, request):
        params = request.query_params
        events, meta = list_audit_events(
            admin=request.user,
            action=params.get('action'),
            actor_id=params.get('actor_id'),
            date_from=params.get('date_from'),
            date_to=params.get('date_to'),
            search=params.get('search'),
            page=params.get('page'),
            page_size=params.get('page_size'),
        )
        return success_response(
            'Audit events.',
            data={
                'events': AuditEventSerializer(events, many=True).data,
                # The action filter's options, so clients never hard-code them.
                'actions': [{'value': value, 'label': label} for value, label in AuditEvent.Action.choices],
                **meta,
            },
        )


class AuditEventDetailView(AuditLogAPIView):
    @extend_schema(responses={200: AuditEventDetailSerializer})
    def get(self, request, event_id):
        event = get_audit_event(admin=request.user, event_id=event_id)
        return success_response('Audit event.', data=AuditEventDetailSerializer(event).data)
