"""
SUPER_ADMIN audit log: read-only access to the existing AuditEvent rows.
Nothing is copied or written here.
"""
from django.db.models import Q

from audit.exceptions import AuditEventNotFound, AuditLogPermissionDenied, InvalidAuditQuery
from audit.models import AuditEvent
from core import listing
from core.permissions import is_super_admin


def _require_super_admin(user):
    if not is_super_admin(user):
        raise AuditLogPermissionDenied()


def list_audit_events(
    *, admin, action=None, actor_id=None, date_from=None, date_to=None, search=None, page=None, page_size=None
):
    """
    Return (events, meta), newest first. action: one AuditEvent.Action value.
    actor_id: the acting user's id. date_from / date_to: inclusive calendar
    dates (YYYY-MM-DD) in the reporting time zone (Asia/Kolkata). search: actor name or
    target type (substring), or - digits only - part of the actor's mobile
    number or the exact target id.
    """
    _require_super_admin(admin)
    queryset = AuditEvent.objects.select_related('actor').order_by('-created_at', '-id')
    action = listing.choice(action, AuditEvent.Action.values, 'action', error=InvalidAuditQuery)
    if action is not None:
        queryset = queryset.filter(action=action)
    if actor_id not in (None, ''):
        try:
            queryset = queryset.filter(actor_id=int(actor_id))
        except (TypeError, ValueError):
            raise InvalidAuditQuery('actor_id must be a whole number.')
    start = listing.date_value(date_from, 'date_from', error=InvalidAuditQuery)
    end = listing.date_value(date_to, 'date_to', error=InvalidAuditQuery)
    if start and end and start > end:
        raise InvalidAuditQuery('date_from must be on or before date_to.')
    if start is not None:
        queryset = queryset.filter(created_at__gte=listing.day_window(start, start)[0])
    if end is not None:
        queryset = queryset.filter(created_at__lt=listing.day_window(end, end)[1])
    query = listing.search_q(
        search,
        text_fields=('actor__name', 'target_type'),
        digit_fields=('actor__mobile_number',),
        error=InvalidAuditQuery,
    )
    if query is not None:
        if search.strip().isdigit():
            query |= Q(target_id=search.strip())
        queryset = queryset.filter(query)
    return listing.paginate(queryset, page, page_size, error=InvalidAuditQuery)


def get_audit_event(*, admin, event_id):
    _require_super_admin(admin)
    try:
        pk = int(event_id)
    except (TypeError, ValueError):
        raise AuditEventNotFound()
    event = AuditEvent.objects.select_related('actor').filter(pk=pk).first()
    if event is None:
        raise AuditEventNotFound()
    return event
