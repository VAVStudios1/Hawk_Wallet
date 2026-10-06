"""
Member home screen: a read-only summary built from the existing Order, PayIn
and Payout records. Nothing here writes, and there is no wallet: every
figure is derived from what the platform owes or has paid on the member's
orders.

Owed money on an order whose pay-in is confirmed and whose payout is not
finished (ACTIVE / PAYOUT_IN_PROGRESS) splits into:
    available = payout_target - payout_committed_total   (not yet committed)
    pending   = PENDING payouts                           (committed, unpaid)
so available + pending = payout_target - payout_paid_total, the outstanding
balance the ledger tracks. Neither is withdrawable on request: admins
create and pay payouts.
"""
from dataclasses import dataclass
from datetime import date, datetime, timedelta
from decimal import Decimal

from django.db.models import CharField, DecimalField, F, Sum, Value
from django.db.models.functions import Coalesce

from core import listing
from core.permissions import is_verified_member
from home.exceptions import HomePermissionDenied, InvalidTransactionQuery
from orders.models import Order
from payins.models import PayIn
from payouts.models import Payout

RECENT_TRANSACTIONS_LIMIT = 5
ZERO = Decimal('0.00')
OPEN_ORDER_STATUSES = (Order.Status.ACTIVE, Order.Status.PAYOUT_IN_PROGRESS)

PAYIN_STATUS = {
    PayIn.Status.CONFIRMED: 'SUCCESS',
    PayIn.Status.UNDER_REVIEW: 'PENDING',
    PayIn.Status.REJECTED: 'REJECTED',
}
PAYOUT_STATUS = {
    Payout.Status.PAID: 'SUCCESS',
    Payout.Status.PENDING: 'PENDING',
    Payout.Status.FAILED: 'FAILED',
    Payout.Status.CANCELLED: 'CANCELLED',
}


@dataclass(frozen=True)
class HomeTransaction:
    type: str
    title: str
    # Signed from the member's side: payments out are negative.
    amount: Decimal
    status: str
    created_at: datetime
    reference: str
    # Stable per record ("PAYIN-12" / "PAYOUT-7"); only the history lists it.
    id: str = ''


@dataclass(frozen=True)
class MemberHome:
    user_id: int
    user_name: str
    available: Decimal
    pending: Decimal
    total_earnings: Decimal
    active_orders: int
    deposit: Decimal
    withdrawal: Decimal
    recent_transactions: list


def get_member_home(*, user):
    """
    Return the MemberHome for `user`, an active verified member. Only the
    member's own orders, pay-ins and payouts are read.

    - available: sum(payout_target - payout_committed_total) over ACTIVE and
      PAYOUT_IN_PROGRESS orders.
    - pending (also summary.pending_payouts): sum of PENDING payouts.
    - total_earnings: sum(income_amount) over COMPLETED orders - income
      actually received.
    - active_orders: count of ACTIVE and PAYOUT_IN_PROGRESS orders.
    - deposit: sum of CONFIRMED pay-ins (under-review and rejected excluded).
    - withdrawal: sum of PAID payouts (pending ones excluded).
    - recent_transactions: the latest 5 pay-ins and payouts, newest first.
    """
    if not is_verified_member(user):
        raise HomePermissionDenied()

    orders = Order.objects.filter(user=user)
    open_orders = orders.filter(status__in=OPEN_ORDER_STATUSES)
    payouts = Payout.objects.filter(user=user)

    return MemberHome(
        user_id=user.pk,
        user_name=user.name,
        available=_sum(open_orders, F('payout_target') - F('payout_committed_total')),
        pending=_sum(payouts.filter(status=Payout.Status.PENDING), F('amount')),
        total_earnings=_sum(orders.filter(status=Order.Status.COMPLETED), F('income_amount')),
        active_orders=open_orders.count(),
        deposit=_sum(
            PayIn.objects.filter(user=user, status=PayIn.Status.CONFIRMED), F('amount')
        ),
        withdrawal=_sum(payouts.filter(status=Payout.Status.PAID), F('amount')),
        recent_transactions=_recent_transactions(user),
    )


def _sum(queryset, expression):
    total = queryset.aggregate(
        total=Coalesce(
            Sum(expression, output_field=DecimalField(max_digits=16, decimal_places=2)),
            Value(ZERO),
            output_field=DecimalField(max_digits=16, decimal_places=2),
        )
    )['total']
    return Decimal(total).quantize(ZERO)


def _recent_transactions(user):
    limit = RECENT_TRANSACTIONS_LIMIT
    payins = (
        PayIn.objects.filter(user=user)
        .select_related('order')
        .order_by('-submitted_at', '-id')[:limit]
    )
    payouts = (
        Payout.objects.filter(user=user)
        .select_related('order')
        .annotate(event_at=Coalesce('paid_at', 'created_at'))
        .order_by('-event_at', '-id')[:limit]
    )
    items = [_payin_item(p) for p in payins] + [_payout_item(p) for p in payouts]
    items.sort(key=lambda item: item.created_at, reverse=True)
    return items[:limit]


def _payin_item(payin):
    rejected = payin.status == PayIn.Status.REJECTED
    return HomeTransaction(
        type='ORDER_PAYMENT',
        title='Order Payment Rejected' if rejected else 'Order Payment',
        amount=-payin.amount,
        status=PAYIN_STATUS[payin.status],
        created_at=payin.submitted_at,
        reference=payin.order.reference,
        id=f'PAYIN-{payin.pk}',
    )


def _payout_item(payout):
    return HomeTransaction(
        type='PAYOUT',
        title='Payout',
        amount=payout.amount,
        status=PAYOUT_STATUS[payout.status],
        created_at=payout.paid_at or payout.created_at,
        reference=payout.order.reference,
        id=f'PAYOUT-{payout.pk}',
    )


# --- transaction history ------------------------------------------------------

HISTORY_RANGES = ('7d', '15d', '30d', 'custom')
HISTORY_DAYS = {'7d': 7, '15d': 15, '30d': 30}
PAYIN_KIND = 'PAYIN'
PAYOUT_KIND = 'PAYOUT'
# type filter: buy = pay-ins (the member pays the platform), sell = payouts
# (the platform pays the member), all = both.
HISTORY_TYPES = ('all', 'buy', 'sell')


@dataclass(frozen=True)
class HistoryPeriod:
    range: str
    start_date: date
    end_date: date


def resolve_history_period(range_value, start_date=None, end_date=None, *, today=None):
    """
    7d / 15d / 30d end on today (7d = today and the 6 days before it), the
    same rule as the admin reports; custom needs start_date and end_date
    (YYYY-MM-DD, start <= end). No range means 7d. Days are calendar days in
    settings.REPORTING_TIME_ZONE.
    """
    today = today or listing.reporting_today()
    value = listing.choice(range_value, HISTORY_RANGES, 'range', error=InvalidTransactionQuery) or '7d'
    if value == 'custom':
        start = listing.date_value(start_date, 'start_date', error=InvalidTransactionQuery)
        end = listing.date_value(end_date, 'end_date', error=InvalidTransactionQuery)
        if start is None or end is None:
            raise InvalidTransactionQuery('A custom range needs start_date and end_date.')
        if start > end:
            raise InvalidTransactionQuery('start_date must be on or before end_date.')
        return HistoryPeriod(value, start, end)
    if start_date not in (None, '') or end_date not in (None, ''):
        raise InvalidTransactionQuery('start_date and end_date are only used with range=custom.')
    return HistoryPeriod(value, today - timedelta(days=HISTORY_DAYS[value] - 1), today)


def list_member_transactions(
    *, user, range_value=None, start_date=None, end_date=None, type_value=None, page=None, page_size=None
):
    """
    Return (period, type, items, meta): one page of the member's own pay-ins
    and payouts - the same records, titles, signed amounts, statuses and dates
    as the Home screen's recent transactions - whose date falls in the period,
    newest first. A pay-in is dated by submitted_at, a payout by paid_at
    (or created_at while unpaid). Read-only.

    type_value narrows the record types: buy = pay-ins only, sell = payouts
    only, all (the default) = both. Ownership, the period and the type are
    applied in the query, which is then ordered and paginated in the database
    (UNION ALL + ORDER BY + LIMIT), never in Python; meta.total counts the
    filtered result.
    """
    if not is_verified_member(user):
        raise HomePermissionDenied()
    type_value = listing.choice(type_value, HISTORY_TYPES, 'type', error=InvalidTransactionQuery) or 'all'
    period = resolve_history_period(range_value, start_date, end_date)
    start, end = listing.day_window(period.start_date, period.end_date)

    columns = ('kind', 'row_id', 'event_at')
    payins = (
        PayIn.objects.filter(user=user)
        .annotate(kind=Value(PAYIN_KIND, output_field=CharField()), row_id=F('id'), event_at=F('submitted_at'))
        .filter(event_at__gte=start, event_at__lt=end)
        .values_list(*columns)
    )
    payouts = (
        Payout.objects.filter(user=user)
        .annotate(
            kind=Value(PAYOUT_KIND, output_field=CharField()),
            row_id=F('id'),
            event_at=Coalesce('paid_at', 'created_at'),
        )
        .filter(event_at__gte=start, event_at__lt=end)
        .values_list(*columns)
    )
    if type_value == 'buy':
        rows = payins
    elif type_value == 'sell':
        rows = payouts
    else:
        rows = payins.union(payouts, all=True)
    rows = rows.order_by('-event_at', 'kind', '-row_id')
    page_rows, meta = listing.paginate(rows, page, page_size, error=InvalidTransactionQuery)

    payin_ids = [row_id for kind, row_id, _ in page_rows if kind == PAYIN_KIND]
    payout_ids = [row_id for kind, row_id, _ in page_rows if kind == PAYOUT_KIND]
    payins_by_id = PayIn.objects.select_related('order').in_bulk(payin_ids)
    payouts_by_id = Payout.objects.select_related('order').in_bulk(payout_ids)
    items = [
        _payin_item(payins_by_id[row_id]) if kind == PAYIN_KIND else _payout_item(payouts_by_id[row_id])
        for kind, row_id, _ in page_rows
    ]
    return period, type_value, items, meta
