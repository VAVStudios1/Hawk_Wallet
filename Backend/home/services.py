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
from datetime import datetime
from decimal import Decimal

from django.db.models import DecimalField, F, Sum, Value
from django.db.models.functions import Coalesce

from core.permissions import is_verified_member
from home.exceptions import HomePermissionDenied
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
    )


def _payout_item(payout):
    return HomeTransaction(
        type='PAYOUT',
        title='Payout',
        amount=payout.amount,
        status=PAYOUT_STATUS[payout.status],
        created_at=payout.paid_at or payout.created_at,
        reference=payout.order.reference,
    )
