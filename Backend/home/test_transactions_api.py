"""API tests for GET /api/home/transactions/ (member transaction history, read-only)."""
from datetime import timedelta
from decimal import Decimal

import pytest
from rest_framework.test import APIClient

from conftest import buy_method_for, next_key
from core import listing
from core.models import User
from home.services import resolve_history_period
from home.exceptions import InvalidTransactionQuery
from payins.models import PayIn
from payins.services import confirm_pay_in, get_pay_in_intent, submit_pay_in_utr
from payouts.models import Payout
from payouts.services import create_payout, mark_payout_paid

URL = '/api/home/transactions/'
_utrs = iter(range(200000000000, 999999999999))


@pytest.fixture
def member(make_user, make_method):
    user = make_user()
    make_method(user, 'history@ybl', is_default=True)
    return user


def _get(user, **params):
    client = APIClient()
    if user is not None:
        client.force_authenticate(user)
    return client.get(URL, params)


def _data(user, **params):
    response = _get(user, **params)
    assert response.status_code == 200, response.data
    return response.data['data']


def _submit(member, order):
    intent = get_pay_in_intent(user=member, order_id=order.pk)
    return submit_pay_in_utr(
        user=member, order_id=order.pk, utr=str(next(_utrs)),
        idempotency_key=next_key(), intent_token=intent.intent_token,
        buy_method_id=buy_method_for(member).pk,
    )


def _confirmed_order(member, make_order, admin):
    order = make_order(member)
    confirm_pay_in(admin=admin, payin_id=_submit(member, order).pk, idempotency_key=next_key())
    order.refresh_from_db()
    return order


def _paid_payout(admin, order, amount):
    payout = create_payout(admin=admin, order_id=order.pk, amount=Decimal(amount), idempotency_key=next_key())
    return mark_payout_paid(admin=admin, payout_id=payout.pk, utr=str(next(_utrs)), idempotency_key=next_key())


def _days_ago(days):
    """Noon IST `days` calendar days before today, as an aware datetime."""
    start, _ = listing.day_window(listing.reporting_today() - timedelta(days=days), listing.reporting_today())
    return start + timedelta(hours=12)


# --- access -------------------------------------------------------------------

def test_anonymous_is_rejected(db):
    assert _get(None).status_code == 401


@pytest.mark.parametrize('extra', [
    {'is_verified': False},
    {'role': User.Role.ADMIN},
])
def test_non_members_are_forbidden(make_user, extra):
    assert _get(make_user(**extra)).status_code == 403


def test_only_own_records(member, make_user, make_method, make_order, receiving_account, admin_user):
    other = make_user()
    make_method(other, 'other-history@ybl', is_default=True)
    _paid_payout(admin_user, _confirmed_order(other, make_order, admin_user), '100')

    assert _data(member)['transactions'] == []
    assert _data(other)['total'] == 2


# --- content ------------------------------------------------------------------

def test_same_items_as_home(member, make_order, receiving_account, admin_user):
    order = _confirmed_order(member, make_order, admin_user)
    payout = _paid_payout(admin_user, order, '100')

    data = _data(member)
    payin = PayIn.objects.get(order=order)
    assert [item['id'] for item in data['transactions']] == [f'PAYOUT-{payout.pk}', f'PAYIN-{payin.pk}']
    payout_item, payin_item = data['transactions']
    assert payout_item['amount'] == '100.00' and payout_item['status'] == 'SUCCESS'
    assert payin_item['amount'] == '-255.00' and payin_item['type'] == 'ORDER_PAYMENT'
    assert payin_item['reference'] == order.reference

    home = APIClient()
    home.force_authenticate(member)
    recent = home.get('/api/home/').data['data']['recent_transactions']
    assert [{k: v for k, v in item.items() if k != 'id'} for item in data['transactions']] == recent


# --- date ranges --------------------------------------------------------------

def test_default_range_is_seven_days(member):
    data = _data(member)
    today = listing.reporting_today()
    assert data['range'] == '7d'
    assert data['end_date'] == today.isoformat()
    assert data['start_date'] == (today - timedelta(days=6)).isoformat()


@pytest.mark.parametrize('range_value, included_days_ago', [('7d', 6), ('15d', 14), ('30d', 29)])
def test_preset_ranges(member, make_order, receiving_account, admin_user, range_value, included_days_ago):
    inside = _submit(member, make_order(member))
    outside = _submit(member, make_order(member))
    PayIn.objects.filter(pk=inside.pk).update(submitted_at=_days_ago(included_days_ago))
    PayIn.objects.filter(pk=outside.pk).update(submitted_at=_days_ago(included_days_ago + 1))

    ids = [item['id'] for item in _data(member, range=range_value)['transactions']]
    assert ids == [f'PAYIN-{inside.pk}']


def test_custom_range_is_inclusive(member, make_order, receiving_account, admin_user):
    first = _submit(member, make_order(member))
    last = _submit(member, make_order(member))
    before = _submit(member, make_order(member))
    PayIn.objects.filter(pk=first.pk).update(submitted_at=_days_ago(40))
    PayIn.objects.filter(pk=last.pk).update(submitted_at=_days_ago(35))
    PayIn.objects.filter(pk=before.pk).update(submitted_at=_days_ago(41))
    today = listing.reporting_today()

    data = _data(
        member, range='custom',
        start_date=(today - timedelta(days=40)).isoformat(),
        end_date=(today - timedelta(days=35)).isoformat(),
    )
    assert [item['id'] for item in data['transactions']] == [f'PAYIN-{last.pk}', f'PAYIN-{first.pk}']


def test_unpaid_payout_dated_by_creation_paid_payout_by_payment(member, make_order, receiving_account, admin_user):
    order = _confirmed_order(member, make_order, admin_user)
    payout = _paid_payout(admin_user, order, '50')
    Payout.objects.filter(pk=payout.pk).update(created_at=_days_ago(20))

    ids = [item['id'] for item in _data(member)['transactions']]
    assert f'PAYOUT-{payout.pk}' in ids     # paid today, so inside 7d


@pytest.mark.parametrize('params', [
    {'range': '90d'},
    {'range': 'custom'},
    {'range': 'custom', 'start_date': '2026-10-05', 'end_date': '2026-10-01'},
    {'range': 'custom', 'start_date': '05-10-2026', 'end_date': '2026-10-06'},
    {'range': '7d', 'start_date': '2026-10-01'},
    {'page': 'x'},
    {'page_size': '101'},
])
def test_invalid_queries(member, params):
    response = _get(member, **params)
    assert response.status_code == 400
    assert response.data['errors']['code'] == 'invalid_query'


def test_resolve_period_rejects_reversed_custom_dates():
    with pytest.raises(InvalidTransactionQuery):
        resolve_history_period('custom', '2026-10-02', '2026-10-01')


# --- pagination and ordering --------------------------------------------------

def test_pages_merge_payins_and_payouts_newest_first(member, make_order, receiving_account, admin_user):
    for _ in range(3):
        _paid_payout(admin_user, _confirmed_order(member, make_order, admin_user), '10')
    # Spread the 6 records over distinct days so the expected order is explicit.
    payins = list(PayIn.objects.filter(user=member).order_by('id'))
    payouts = list(Payout.objects.filter(user=member).order_by('id'))
    for index, record in enumerate(payins):
        PayIn.objects.filter(pk=record.pk).update(submitted_at=_days_ago(6 - 2 * index))
    for index, record in enumerate(payouts):
        Payout.objects.filter(pk=record.pk).update(paid_at=_days_ago(5 - 2 * index))

    first = _data(member, page_size=4)
    second = _data(member, page_size=4, page=2)
    assert (first['total'], first['has_next'], second['has_next']) == (6, True, False)
    ids = [item['id'] for item in first['transactions'] + second['transactions']]
    expected = []
    for payin, payout in zip(payins, payouts):
        expected += [f'PAYIN-{payin.pk}', f'PAYOUT-{payout.pk}']
    assert ids == list(reversed(expected))


def test_page_past_the_end_is_rejected(member, make_order, receiving_account, admin_user):
    _submit(member, make_order(member))
    assert _get(member, page=2).status_code == 400


# --- type filter (buy = pay-ins, sell = payouts) --------------------------------

def _spread_history(member, make_order, admin_user, orders=3):
    """`orders` pay-ins and paid payouts on distinct days, oldest first: payin, payout, payin, ..."""
    for _ in range(orders):
        _paid_payout(admin_user, _confirmed_order(member, make_order, admin_user), '10')
    payins = list(PayIn.objects.filter(user=member).order_by('id'))
    payouts = list(Payout.objects.filter(user=member).order_by('id'))
    for index, record in enumerate(payins):
        PayIn.objects.filter(pk=record.pk).update(submitted_at=_days_ago(2 * orders - 2 * index))
    for index, record in enumerate(payouts):
        Payout.objects.filter(pk=record.pk).update(paid_at=_days_ago(2 * orders - 1 - 2 * index))
    return payins, payouts


def _ids(data):
    return [item['id'] for item in data['transactions']]


def test_type_defaults_to_all_and_all_returns_both(member, make_order, receiving_account, admin_user):
    payins, payouts = _spread_history(member, make_order, admin_user, orders=2)
    default = _data(member)
    explicit = _data(member, type='all')
    assert default['type'] == explicit['type'] == 'all'
    assert _ids(default) == _ids(explicit) == [
        f'PAYOUT-{payouts[1].pk}', f'PAYIN-{payins[1].pk}', f'PAYOUT-{payouts[0].pk}', f'PAYIN-{payins[0].pk}',
    ]
    assert explicit['total'] == 4


def test_buy_returns_only_payins_newest_first(member, make_order, receiving_account, admin_user):
    payins, _ = _spread_history(member, make_order, admin_user)
    data = _data(member, type='buy')
    assert data['type'] == 'buy' and data['total'] == 3
    assert _ids(data) == [f'PAYIN-{p.pk}' for p in reversed(payins)]
    assert {item['type'] for item in data['transactions']} == {'ORDER_PAYMENT'}


def test_sell_returns_only_payouts_newest_first(member, make_order, receiving_account, admin_user):
    _, payouts = _spread_history(member, make_order, admin_user)
    data = _data(member, type='sell')
    assert data['type'] == 'sell' and data['total'] == 3
    assert _ids(data) == [f'PAYOUT-{p.pk}' for p in reversed(payouts)]
    assert {item['type'] for item in data['transactions']} == {'PAYOUT'}


@pytest.mark.parametrize('type_value, kind', [('buy', 'PAYIN'), ('sell', 'PAYOUT')])
def test_type_with_preset_range(member, make_order, receiving_account, admin_user, type_value, kind):
    payins, payouts = _spread_history(member, make_order, admin_user)   # days 6..1 ago
    records = payins if kind == 'PAYIN' else payouts
    PayIn.objects.filter(pk=payins[0].pk).update(submitted_at=_days_ago(20))
    Payout.objects.filter(pk=payouts[0].pk).update(paid_at=_days_ago(20))

    data = _data(member, range='7d', type=type_value)
    assert data['total'] == 2
    assert _ids(data) == [f'{kind}-{r.pk}' for r in reversed(records[1:])]
    assert _data(member, range='30d', type=type_value)['total'] == 3


@pytest.mark.parametrize('type_value, kind', [('buy', 'PAYIN'), ('sell', 'PAYOUT')])
def test_type_with_custom_range(member, make_order, receiving_account, admin_user, type_value, kind):
    payins, payouts = _spread_history(member, make_order, admin_user)
    # payins: 6, 4, 2 days ago; payouts: 5, 3, 1 days ago.
    today = listing.reporting_today()
    data = _data(
        member, range='custom', type=type_value,
        start_date=(today - timedelta(days=5)).isoformat(),
        end_date=(today - timedelta(days=2)).isoformat(),
    )
    expected = [payins[2], payins[1]] if kind == 'PAYIN' else [payouts[1], payouts[0]]
    assert _ids(data) == [f'{kind}-{r.pk}' for r in expected]
    assert data['total'] == 2


@pytest.mark.parametrize('type_value, kind', [('buy', 'PAYIN'), ('sell', 'PAYOUT')])
def test_type_filter_paginates_after_filtering(member, make_order, receiving_account, admin_user, type_value, kind):
    payins, payouts = _spread_history(member, make_order, admin_user)
    records = payins if kind == 'PAYIN' else payouts
    first = _data(member, type=type_value, page_size=2)
    second = _data(member, type=type_value, page_size=2, page=2)
    assert (first['total'], first['has_next'], second['has_next']) == (3, True, False)
    assert _ids(first) + _ids(second) == [f'{kind}-{r.pk}' for r in reversed(records)]


@pytest.mark.parametrize('type_value', ['BUY', 'Sell', 'payin', 'x', ' all'])
def test_invalid_type_is_rejected(member, type_value):
    response = _get(member, type=type_value)
    assert response.status_code == 400
    assert response.data['errors']['code'] == 'invalid_query'


@pytest.mark.parametrize('type_value', ['all', 'buy', 'sell'])
def test_type_filter_keeps_ownership(member, make_user, make_method, make_order, receiving_account, admin_user,
                                     type_value):
    other = make_user()
    make_method(other, 'other-type@ybl', is_default=True)
    _paid_payout(admin_user, _confirmed_order(other, make_order, admin_user), '100')

    data = _data(member, type=type_value)
    assert data['transactions'] == [] and data['total'] == 0
    assert _data(other, type=type_value)['total'] == (2 if type_value == 'all' else 1)
