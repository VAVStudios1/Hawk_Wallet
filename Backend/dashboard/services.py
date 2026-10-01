"""
Admin dashboard: read-only operational counts built from the existing User,
Order, PayIn, Payout and Conversation records. Nothing here writes, and no
money figure is returned - only counts.

Visibility follows the existing admin rules unchanged: any active admin may
review every order, pay-in and payout (see orders.services.list_admin_orders),
so those counts are platform-wide; support follows the support app's own
scoping (ADMIN: assigned conversations, SUPER_ADMIN: all).

- total_users: accounts with the MEMBER role, whatever their status.
- active_orders: ACTIVE and PAYOUT_IN_PROGRESS orders - the same rule as
  the member home screen's active_orders.
- pending_payins: pay-ins UNDER_REVIEW (awaiting an admin's confirm/reject).
- pending_payouts: payouts PENDING (created, not yet paid or cancelled).
- open_support: OPEN conversations visible to the caller.
- orders_awaiting_action: orders an admin has a pending step on - see
  orders.services.ORDER_STATUSES_AWAITING_ADMIN_ACTION for why each status
  qualifies.
"""
from dataclasses import dataclass

from core.models import User
from core.permissions import is_active_admin
from dashboard.exceptions import DashboardPermissionDenied
from home.services import OPEN_ORDER_STATUSES
from orders.models import Order
from orders.services import orders_awaiting_admin_action
from payins.models import PayIn
from payouts.models import Payout
from support.services import count_open_admin_conversations


@dataclass(frozen=True)
class AdminDashboard:
    total_users: int
    active_orders: int
    pending_payins: int
    pending_payouts: int
    open_support: int
    orders_awaiting_action: int


def get_admin_dashboard(*, admin):
    if not is_active_admin(admin):
        raise DashboardPermissionDenied()
    return AdminDashboard(
        total_users=User.objects.filter(role=User.Role.MEMBER).count(),
        active_orders=Order.objects.filter(status__in=OPEN_ORDER_STATUSES).count(),
        pending_payins=PayIn.objects.filter(status=PayIn.Status.UNDER_REVIEW).count(),
        pending_payouts=Payout.objects.filter(status=Payout.Status.PENDING).count(),
        open_support=count_open_admin_conversations(admin=admin),
        orders_awaiting_action=orders_awaiting_admin_action().count(),
    )
