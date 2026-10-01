"""
Admin reports, computed on the server from existing records. Nothing here
writes. Money is summed as Decimal in the database and returned as the
usual 2-place strings; nothing is recomputed from rates or targets.

Operational report (ADMIN and SUPER_ADMIN, platform-wide like every admin
read): every section counts the records CREATED in the date range, broken
down by their CURRENT status, so each figure has one unambiguous basis:
- orders:   claimed_at in range
- pay_ins:  submitted_at in range; confirmed_amount = amounts of those now CONFIRMED
- payouts:  created_at in range; paid_amount / pending_amount = amounts of those now PAID / PENDING
- members:  MEMBER accounts with date_joined in range; verified / active = how many of them are now
- support:  conversations created in range
Dates are inclusive calendar days in settings.REPORTING_TIME_ZONE (Asia/Kolkata).

System report (SUPER_ADMIN only): platform-wide totals as of now, plus each
admin's workload (accounts.admin_management.with_admin_workload). It is
listed by name - an overview, never a ranking.
"""
from dataclasses import dataclass
from datetime import date, timedelta
from decimal import Decimal

from django.db.models import Count, DecimalField, Sum, Value
from django.db.models.functions import Coalesce

from accounts.admin_management import ADMIN_ROLES, with_admin_workload
from core import listing
from core.models import User
from core.permissions import is_active_admin, is_super_admin
from dashboard.exceptions import DashboardPermissionDenied, InvalidReportQuery, SystemReportPermissionDenied
from home.services import OPEN_ORDER_STATUSES
from orders.models import Order
from payins.models import PayIn
from payouts.models import Payout
from support.models import Conversation
from support.services import stranded_open_conversations

RANGES = ('today', '7d', '30d', 'custom')
ZERO = Decimal('0.00')


@dataclass(frozen=True)
class ReportPeriod:
    range: str
    start_date: date
    end_date: date


def resolve_period(range_value, start_date=None, end_date=None, *, today=None):
    """
    today / 7d / 30d end on today (7d = today and the 6 days before it);
    custom needs start_date and end_date (YYYY-MM-DD, start <= end).
    No range means today.
    """
    today = today or listing.reporting_today()
    value = listing.choice(range_value, RANGES, 'range', error=InvalidReportQuery) or 'today'
    if value == 'custom':
        start = listing.date_value(start_date, 'start_date', error=InvalidReportQuery)
        end = listing.date_value(end_date, 'end_date', error=InvalidReportQuery)
        if start is None or end is None:
            raise InvalidReportQuery('A custom range needs start_date and end_date.')
        if start > end:
            raise InvalidReportQuery('start_date must be on or before end_date.')
        return ReportPeriod(value, start, end)
    if start_date not in (None, '') or end_date not in (None, ''):
        raise InvalidReportQuery('start_date and end_date are only used with range=custom.')
    days = {'today': 1, '7d': 7, '30d': 30}[value]
    return ReportPeriod(value, today - timedelta(days=days - 1), today)


def _by_status(queryset, statuses):
    counted = dict(queryset.order_by().values_list('status').annotate(n=Count('id')).values_list('status', 'n'))
    return {status: counted.get(status, 0) for status in statuses}


def _amount(queryset):
    return queryset.aggregate(
        total=Coalesce(Sum('amount'), Value(ZERO), output_field=DecimalField(max_digits=16, decimal_places=2))
    )['total']


def get_report(*, admin, range_value=None, start_date=None, end_date=None):
    if not is_active_admin(admin):
        raise DashboardPermissionDenied()
    period = resolve_period(range_value, start_date, end_date)
    start, end = listing.day_window(period.start_date, period.end_date)

    orders = Order.objects.filter(claimed_at__gte=start, claimed_at__lt=end)
    pay_ins = PayIn.objects.filter(submitted_at__gte=start, submitted_at__lt=end)
    payouts = Payout.objects.filter(created_at__gte=start, created_at__lt=end)
    members = User.objects.filter(role=User.Role.MEMBER, date_joined__gte=start, date_joined__lt=end)
    conversations = Conversation.objects.filter(created_at__gte=start, created_at__lt=end)

    return {
        'range': period.range,
        'start_date': period.start_date,
        'end_date': period.end_date,
        'timezone': str(listing.reporting_time_zone()),
        'orders': {
            'total': orders.count(),
            'by_status': _by_status(orders, Order.Status.values),
        },
        'pay_ins': {
            'total': pay_ins.count(),
            'by_status': _by_status(pay_ins, PayIn.Status.values),
            'confirmed_amount': _amount(pay_ins.filter(status=PayIn.Status.CONFIRMED)),
        },
        'payouts': {
            'total': payouts.count(),
            'by_status': _by_status(payouts, Payout.Status.values),
            'paid_amount': _amount(payouts.filter(status=Payout.Status.PAID)),
            'pending_amount': _amount(payouts.filter(status=Payout.Status.PENDING)),
        },
        'members': {
            'new': members.count(),
            'verified': members.filter(is_verified=True).count(),
            'active': members.filter(is_active=True).count(),
        },
        'support': {
            'total': conversations.count(),
            'by_status': _by_status(conversations, Conversation.Status.values),
        },
    }


def get_system_report(*, admin):
    if not is_super_admin(admin):
        raise SystemReportPermissionDenied()
    admins = User.objects.filter(role__in=ADMIN_ROLES)
    members = User.objects.filter(role=User.Role.MEMBER)
    return {
        'admins': {'total': admins.count(), 'active': admins.filter(is_active=True).count()},
        'members': {'total': members.count(), 'active': members.filter(is_active=True).count()},
        'active_orders': Order.objects.filter(status__in=OPEN_ORDER_STATUSES).count(),
        'pending_payins': PayIn.objects.filter(status=PayIn.Status.UNDER_REVIEW).count(),
        'pending_payouts': Payout.objects.filter(status=Payout.Status.PENDING).count(),
        'open_support': Conversation.objects.filter(status=Conversation.Status.OPEN).count(),
        # OPEN conversations no active ADMIN can answer (e.g. no ADMIN left when theirs was deactivated).
        'stranded_open_support': stranded_open_conversations().count(),
        'workload': list(with_admin_workload(admins).order_by('name', 'id')),
    }
