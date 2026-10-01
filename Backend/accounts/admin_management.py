"""
SUPER_ADMIN admin management: list, inspect, create (ADMIN role only) and
activate / deactivate admin accounts.

- Only an active SUPER_ADMIN may call any of this (the views also gate on
  IsSuperAdmin).
- Creation always makes an ADMIN - never a SUPER_ADMIN - through the normal
  create_user path. The account starts unverified, exactly like a member's:
  its first login sends a registration OTP to the mobile number, so the
  mobile is proven the same way as for every other account.
- Activation / deactivation applies to ADMIN accounts only, never to the
  caller's own account. Nothing is deleted: a deactivated admin keeps every
  order, review, payout, audit and closed support record. Their sessions
  are revoked, and their OPEN support conversations move to other active
  ADMINs by the normal routing rule (support.services.
  reassign_open_conversations); with no eligible ADMIN they stay put and
  show as stranded.
- Every change records one AuditEvent naming the acting SUPER_ADMIN.

Workload counts are only what the data can attribute to an admin: support
conversations are assigned to one admin, payouts record who created and
who paid them, pay-ins record who reviewed them. Members, orders and
pending pay-ins belong to no admin, so they have no per-admin count.
"""
from django.db import IntegrityError, transaction
from django.db.models import Count, IntegerField, OuterRef, Subquery, Value
from django.db.models.functions import Coalesce

from accounts.exceptions import (
    AdminAccountNotFound,
    AdminManagementPermissionDenied,
    AdminNotManageable,
    CannotManageOwnAccount,
    InvalidAdminQuery,
    MobileNumberTaken,
)
from audit.models import AuditEvent
from audit.services import record_audit_event
from core import listing
from core.models import User
from core.permissions import is_super_admin
from core.tokens import revoke_all_refresh_tokens
from payins.models import PayIn
from payouts.models import Payout
from support.models import Conversation
from support.services import reassign_open_conversations

ADMIN_ROLES = (User.Role.ADMIN, User.Role.SUPER_ADMIN)


def _require_super_admin(user):
    if not is_super_admin(user):
        raise AdminManagementPermissionDenied()


def _count(queryset, field):
    """Correlated count of `queryset` rows whose `field` is the outer user."""
    return Coalesce(
        Subquery(
            queryset.filter(**{field: OuterRef('pk')})
            .order_by()
            .values(field)
            .annotate(n=Count('pk'))
            .values('n')[:1],
            output_field=IntegerField(),
        ),
        Value(0),
    )


def with_admin_workload(queryset):
    """
    Annotate admin accounts with what the data attributes to each one:
    open support conversations assigned to them, payouts they created that
    are still PENDING, pay-ins they reviewed and payouts they marked PAID.
    """
    return queryset.annotate(
        open_support_conversations=_count(
            Conversation.objects.filter(status=Conversation.Status.OPEN), 'assigned_admin'
        ),
        pending_payouts_created=_count(Payout.objects.filter(status=Payout.Status.PENDING), 'created_by'),
        pay_ins_reviewed=_count(
            PayIn.objects.filter(status__in=PayIn.final_statuses), 'reviewed_by'
        ),
        payouts_paid=_count(Payout.objects.filter(status=Payout.Status.PAID), 'paid_by'),
    )


def _admins():
    return User.objects.filter(role__in=ADMIN_ROLES)


def list_admins(*, admin, is_active=None, search=None, page=None, page_size=None):
    """
    Return (admins, meta): ADMIN and SUPER_ADMIN accounts with their
    workload, newest first. is_active: 'true' / 'false'. search: name, or -
    digits only - part of the mobile number.
    """
    _require_super_admin(admin)
    queryset = with_admin_workload(_admins()).order_by('-date_joined', '-id')
    active = listing.boolean(is_active, 'is_active', error=InvalidAdminQuery)
    if active is not None:
        queryset = queryset.filter(is_active=active)
    query = listing.search_q(
        search, text_fields=('name',), digit_fields=('mobile_number',), error=InvalidAdminQuery
    )
    if query is not None:
        queryset = queryset.filter(query)
    return listing.paginate(queryset, page, page_size, error=InvalidAdminQuery)


def get_admin(*, admin, admin_id):
    _require_super_admin(admin)
    try:
        pk = int(admin_id)
    except (TypeError, ValueError):
        raise AdminAccountNotFound()
    account = with_admin_workload(_admins()).filter(pk=pk).first()
    if account is None:
        raise AdminAccountNotFound()
    return account


def create_admin(*, admin, name, mobile_number, password, ip_address=None):
    """
    Create one ADMIN account. Input is already validated by
    CreateAdminSerializer (name, mobile format, password rules); the role is
    fixed here and can never be SUPER_ADMIN. A concurrent registration of
    the same number raises MobileNumberTaken.
    """
    _require_super_admin(admin)
    try:
        with transaction.atomic():
            account = User.objects.create_user(
                mobile_number=mobile_number,
                name=name,
                password=password,
                role=User.Role.ADMIN,
                is_verified=False,
            )
            record_audit_event(
                actor=admin,
                action=AuditEvent.Action.ADMIN_CREATED,
                target=account,
                ip_address=ip_address,
                metadata={'role': User.Role.ADMIN},
            )
    except IntegrityError:
        raise MobileNumberTaken(errors={'mobile_number': [MobileNumberTaken.default_message]})
    return get_admin(admin=admin, admin_id=account.pk)


def set_admin_active(*, admin, admin_id, is_active, ip_address=None):
    """
    Activate or deactivate an ADMIN account (never a SUPER_ADMIN, never the
    caller). Repeating the current state changes nothing and records no
    event. Deactivation revokes every live refresh token of the account.
    """
    _require_super_admin(admin)
    try:
        pk = int(admin_id)
    except (TypeError, ValueError):
        raise AdminAccountNotFound()
    if pk == admin.pk:
        raise CannotManageOwnAccount()
    with transaction.atomic():
        account = _admins().select_for_update().filter(pk=pk).first()
        if account is None:
            raise AdminAccountNotFound()
        if account.role != User.Role.ADMIN:
            raise AdminNotManageable()
        if account.is_active != is_active:
            account.is_active = is_active
            account.save(update_fields=['is_active'])
            metadata = {'role': account.role}
            if not is_active:
                metadata['revoked_refresh_tokens'] = revoke_all_refresh_tokens(account)
                reassigned, stranded = reassign_open_conversations(
                    from_admin=account, actor=admin, ip_address=ip_address
                )
                metadata['support_reassigned'] = reassigned
                metadata['support_stranded'] = stranded
            record_audit_event(
                actor=admin,
                action=AuditEvent.Action.ADMIN_ACTIVATED if is_active else AuditEvent.Action.ADMIN_DEACTIVATED,
                target=account,
                ip_address=ip_address,
                metadata=metadata,
            )
    return get_admin(admin=admin, admin_id=pk)
