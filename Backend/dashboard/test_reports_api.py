"""API tests for GET /api/admin/reports/ (ADMIN + SUPER_ADMIN) and /api/admin/system-report/ (SUPER_ADMIN)."""
from datetime import date, datetime, timedelta, timezone as dt_timezone
from decimal import Decimal

import pytest
from rest_framework.test import APIClient

from conftest import buy_method_for, next_key
from core import listing
from core.models import User
from dashboard.exceptions import InvalidReportQuery
from dashboard.reports import resolve_period
from orders.models import Order
from payins.services import confirm_pay_in, get_pay_in_intent, submit_pay_in_utr
from payouts.services import create_payout, mark_payout_paid
from support.models import Conversation
from support.services import close_conversation

REPORT_URL = '/api/admin/reports/'
SYSTEM_URL = '/api/admin/system-report/'
_utrs = iter(range(600000000000, 999999999999))


@pytest.fixture
def super_admin(make_user):
    return make_user(role=User.Role.SUPER_ADMIN)


def _client(user):
    client = APIClient()
    client.force_authenticate(user)
    return client


def _data(user, url=REPORT_URL, **params):
    response = _client(user).get(url, params)
    assert response.status_code == 200, response.data
    return response.data['data']


def _submit(member, order):
    intent = get_pay_in_intent(user=member, order_id=order.pk)
    return submit_pay_in_utr(
        user=member, order_id=order.pk, utr=str(next(_utrs)), idempotency_key=next_key(),
        intent_token=intent.intent_token, buy_method_id=buy_method_for(member).pk,
    )


def _named(make_user, name, **extra):
    user = make_user(**extra)
    user.name = name
    user.save(update_fields=['name'])
    return user


# --- periods ------------------------------------------------------------------

def test_periods_end_today():
    today = date(2026, 10, 1)
    assert resolve_period(None, today=today) == resolve_period('today', today=today)
    assert (resolve_period('today', today=today).start_date, resolve_period('today', today=today).end_date) == (today, today)
    assert resolve_period('7d', today=today).start_date == date(2026, 9, 25)
    assert resolve_period('30d', today=today).start_date == date(2026, 9, 2)
    custom = resolve_period('custom', '2026-09-01', '2026-09-15', today=today)
    assert (custom.start_date, custom.end_date) == (date(2026, 9, 1), date(2026, 9, 15))


@pytest.mark.parametrize('args', [
    ('week',),
    ('custom',),
    ('custom', '2026-09-01'),
    ('custom', '2026-09-15', '2026-09-01'),
    ('custom', '15/09/2026', '2026-09-20'),
    ('7d', '2026-09-01', '2026-09-15'),
])
def test_invalid_periods(args):
    with pytest.raises(InvalidReportQuery):
        resolve_period(*args, today=date(2026, 10, 1))


# --- access -------------------------------------------------------------------

def test_report_for_both_admin_roles_only(super_admin, admin_user, make_user):
    for user in (admin_user, super_admin):
        assert _client(user).get(REPORT_URL, {'range': '7d'}).status_code == 200
    assert _client(make_user()).get(REPORT_URL).status_code == 403
    assert APIClient().get(REPORT_URL).status_code == 401
    response = _client(admin_user).get(REPORT_URL, {'range': 'year'})
    assert response.status_code == 400 and response.data['errors']['code'] == 'invalid_query'


def test_system_report_super_admin_only(super_admin, admin_user, make_user):
    assert _client(super_admin).get(SYSTEM_URL).status_code == 200
    for user in (admin_user, make_user()):
        assert _client(user).get(SYSTEM_URL).status_code == 403
    assert APIClient().get(SYSTEM_URL).status_code == 401


# --- report values ------------------------------------------------------------

def test_report_counts_and_amounts(super_admin, admin_user, make_user, make_method, make_order, receiving_account):
    member = make_user()
    make_method(member, 'r@ybl', is_default=True)
    make_user(is_verified=False)  # a second new member, unverified

    make_order(member)                                            # PAYMENT_REQUIRED
    make_order(member, status=Order.Status.CANCELLED)             # CANCELLED
    _submit(member, make_order(member))                           # pay-in UNDER_REVIEW
    paid_order = make_order(member)
    confirm_pay_in(admin=admin_user, payin_id=_submit(member, paid_order).pk, idempotency_key=next_key())
    paid = create_payout(admin=admin_user, order_id=paid_order.pk, amount=Decimal('100'), idempotency_key=next_key())
    mark_payout_paid(admin=admin_user, payout_id=paid.pk, utr=str(next(_utrs)), idempotency_key=next_key())
    create_payout(admin=admin_user, order_id=paid_order.pk, amount=Decimal('65'), idempotency_key=next_key())
    open_conversation = Conversation.objects.create(user=member, assigned_admin=admin_user)
    closed = Conversation.objects.create(user=make_user(), assigned_admin=admin_user)
    close_conversation(admin=admin_user, conversation_id=closed.public_id)

    data = _data(super_admin, range='today')
    assert data['start_date'] == data['end_date'] == str(listing.reporting_today())
    assert data['timezone'] == 'Asia/Kolkata'
    assert data['orders']['total'] == 4
    assert data['orders']['by_status']['CANCELLED'] == 1
    assert data['orders']['by_status']['PAYMENT_REQUIRED'] == 1
    assert data['orders']['by_status']['PAYMENT_UNDER_REVIEW'] == 1
    assert data['orders']['by_status']['PAYOUT_IN_PROGRESS'] == 1
    assert set(data['orders']['by_status']) == set(Order.Status.values)
    assert data['pay_ins']['total'] == 2
    assert data['pay_ins']['by_status'] == {'UNDER_REVIEW': 1, 'CONFIRMED': 1, 'REJECTED': 0}
    assert data['pay_ins']['confirmed_amount'] == '255.00'
    assert data['payouts']['total'] == 2
    assert data['payouts']['by_status']['PAID'] == 1 and data['payouts']['by_status']['PENDING'] == 1
    assert data['payouts']['paid_amount'] == '100.00'
    assert data['payouts']['pending_amount'] == '65.00'
    # Members: the three created by this test (two named above + the closed chat's member).
    assert data['members'] == {'new': 3, 'verified': 2, 'active': 3}
    assert data['support'] == {'total': 2, 'by_status': {'OPEN': 1, 'CLOSED': 1}}
    assert open_conversation.status == Conversation.Status.OPEN


def test_custom_range_excludes_records_outside_it(admin_user, make_user, make_method, make_order):
    member = make_user()
    make_method(member, 'c@ybl', is_default=True)
    make_order(member)
    yesterday = listing.reporting_today() - timedelta(days=1)
    data = _data(admin_user, range='custom', start_date=str(yesterday - timedelta(days=5)), end_date=str(yesterday))
    assert data['orders']['total'] == 0
    assert data['members']['new'] == 0
    assert data['pay_ins']['confirmed_amount'] == '0.00'


# --- system report ------------------------------------------------------------

def test_system_report_totals_and_workload(super_admin, make_user, make_method, make_order):
    ravi = _named(make_user, 'Ravi Admin', role=User.Role.ADMIN)
    asha = _named(make_user, 'Asha Admin', role=User.Role.ADMIN, is_active=False)
    member = make_user()
    make_user(is_active=False)
    make_method(member, 's@ybl', is_default=True)
    make_order(member, status=Order.Status.ACTIVE)
    Conversation.objects.create(user=member, assigned_admin=ravi)

    data = _data(super_admin, SYSTEM_URL)
    assert data['admins'] == {'total': 3, 'active': 2}
    assert data['members'] == {'total': 2, 'active': 1}
    assert data['active_orders'] == 1
    assert data['pending_payins'] == 0 and data['pending_payouts'] == 0
    assert data['open_support'] == 1
    assert data['stranded_open_support'] == 0
    rows = {row['id']: row for row in data['workload']}
    assert set(rows) == {super_admin.pk, ravi.pk, asha.pk}
    assert rows[ravi.pk]['open_support_conversations'] == 1
    assert rows[asha.pk]['is_active'] is False
    # Listed by name: an overview, not a ranking.
    names = [row['name'] for row in data['workload']]
    assert names == sorted(names)


# --- reporting time zone (Asia/Kolkata) -------------------------------------

def test_day_window_uses_ist_boundaries():
    start, end = listing.day_window(date(2026, 10, 2), date(2026, 10, 2))
    # 2 Oct IST = 1 Oct 18:30 UTC up to 2 Oct 18:30 UTC.
    assert start.astimezone(dt_timezone.utc) == datetime(2026, 10, 1, 18, 30, tzinfo=dt_timezone.utc)
    assert end.astimezone(dt_timezone.utc) == datetime(2026, 10, 2, 18, 30, tzinfo=dt_timezone.utc)


def test_custom_range_counts_by_ist_day(admin_user, make_user, make_method, make_order):
    member = make_user()
    make_method(member, 'tz@ybl', is_default=True)
    # 20:00 UTC on 1 Oct is 01:30 IST on 2 Oct: it belongs to 2 Oct, not 1 Oct.
    make_order(member, claimed_at=datetime(2026, 10, 1, 20, 0, tzinfo=dt_timezone.utc))
    on_first = _data(admin_user, range='custom', start_date='2026-10-01', end_date='2026-10-01')
    on_second = _data(admin_user, range='custom', start_date='2026-10-02', end_date='2026-10-02')
    assert on_first['orders']['total'] == 0
    assert on_second['orders']['total'] == 1


def test_stranded_conversations_are_visible(super_admin, make_user):
    gone = make_user(role=User.Role.ADMIN, is_active=False)
    Conversation.objects.create(user=make_user(), assigned_admin=gone)
    assert _data(super_admin, SYSTEM_URL)['stranded_open_support'] == 1
