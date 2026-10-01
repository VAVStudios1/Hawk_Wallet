"""
Admin user directory: read-only views of member accounts for active admins.

There is no user-to-admin relationship, so - like orders, pay-ins and
payouts - every active admin (ADMIN or SUPER_ADMIN) sees every member. Only
MEMBER accounts are listed; admin accounts are not part of this directory.
Nothing here writes, and no secret (password hash, OTP, token) is ever read
into a result.
"""
from dataclasses import dataclass

from django.db.models import Count

from accounts.exceptions import AdminUserNotFound, AdminUserPermissionDenied, InvalidAdminUserQuery
from core import listing
from core.models import User
from core.permissions import is_active_admin
from orders.models import Order
from payins.models import PayIn

RECENT_ORDERS_LIMIT = 5


@dataclass(frozen=True)
class AdminUserDetail:
    user: User
    orders_total: int
    order_counts: dict
    pay_in_counts: dict
    recent_orders: list


def _require_admin(user):
    if not is_active_admin(user):
        raise AdminUserPermissionDenied()


def _members():
    return User.objects.filter(role=User.Role.MEMBER)


def list_admin_users(*, admin, is_active=None, is_verified=None, search=None, page=None, page_size=None):
    """
    Return (users, meta): one page of member accounts, newest first.
    is_active / is_verified: 'true' or 'false'. search: name (substring), or -
    digits only - part of the mobile number.
    """
    _require_admin(admin)
    queryset = _members().order_by('-date_joined', '-id')
    active = listing.boolean(is_active, 'is_active', error=InvalidAdminUserQuery)
    if active is not None:
        queryset = queryset.filter(is_active=active)
    verified = listing.boolean(is_verified, 'is_verified', error=InvalidAdminUserQuery)
    if verified is not None:
        queryset = queryset.filter(is_verified=verified)
    query = listing.search_q(
        search, text_fields=('name',), digit_fields=('mobile_number',), error=InvalidAdminUserQuery
    )
    if query is not None:
        queryset = queryset.filter(query)
    return listing.paginate(queryset, page, page_size, error=InvalidAdminUserQuery)


def _counts_by_status(queryset, statuses):
    counted = dict(queryset.values_list('status').annotate(n=Count('id')).values_list('status', 'n'))
    return {status: counted.get(status, 0) for status in statuses}


def get_admin_user(*, admin, user_id):
    """
    One member account with its order and pay-in activity, all counted on
    the server: order and pay-in counts per status and the most recent
    orders. Unknown ids and non-member accounts raise AdminUserNotFound.
    """
    _require_admin(admin)
    try:
        pk = int(user_id)
    except (TypeError, ValueError):
        raise AdminUserNotFound()
    user = _members().filter(pk=pk).first()
    if user is None:
        raise AdminUserNotFound()
    orders = Order.objects.filter(user=user)
    return AdminUserDetail(
        user=user,
        orders_total=orders.count(),
        order_counts=_counts_by_status(orders, Order.Status.values),
        pay_in_counts=_counts_by_status(PayIn.objects.filter(user=user), PayIn.Status.values),
        recent_orders=list(orders.order_by('-claimed_at', '-id')[:RECENT_ORDERS_LIMIT]),
    )
