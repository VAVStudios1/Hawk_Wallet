"""API tests for GET /api/admin/orders/ pagination, status filter and search."""
import pytest
from rest_framework.test import APIClient

from core.models import User
from orders.models import Order

URL = '/api/admin/orders/'


@pytest.fixture
def super_admin(make_user):
    return make_user(role=User.Role.SUPER_ADMIN)


def _get(user, **params):
    client = APIClient()
    client.force_authenticate(user)
    return client.get(URL, params)


def _data(user, **params):
    response = _get(user, **params)
    assert response.status_code == 200, response.data
    return response.data['data']


def _member(make_user, make_method, name='Asha Member'):
    member = _named(make_user, name)
    make_method(member, f'm{member.pk}@ybl', is_default=True)
    return member


def _named(make_user, name, **extra):
    user = make_user(**extra)
    user.name = name
    user.save(update_fields=['name'])
    return user


# --- access -------------------------------------------------------------------

def test_admin_and_super_admin_can_list(admin_user, super_admin):
    for viewer in (admin_user, super_admin):
        data = _data(viewer)
        assert set(data) == {'orders', 'page', 'page_size', 'total', 'has_next'}


def test_member_forbidden_and_anonymous_rejected(make_user):
    assert _get(make_user()).status_code == 403
    assert APIClient().get(URL).status_code == 401


# --- pagination ---------------------------------------------------------------

def test_pages_newest_first(admin_user, make_user, make_method, make_order):
    member = _member(make_user, make_method)
    orders = [make_order(member) for _ in range(3)]

    first = _data(admin_user, page_size=2)
    assert [o['id'] for o in first['orders']] == [orders[2].pk, orders[1].pk]
    assert (first['page'], first['page_size'], first['total'], first['has_next']) == (1, 2, 3, True)

    second = _data(admin_user, page_size=2, page=2)
    assert [o['id'] for o in second['orders']] == [orders[0].pk]
    assert second['has_next'] is False


def test_default_page_size_is_twenty(admin_user, make_user, make_method, make_order):
    member = _member(make_user, make_method)
    for _ in range(21):
        make_order(member)
    data = _data(admin_user)
    assert len(data['orders']) == 20
    assert data['total'] == 21 and data['has_next'] is True


@pytest.mark.parametrize('params', [
    {'page': 2},            # beyond the last page
    {'page': 0},
    {'page': 'x'},
    {'page_size': 0},
    {'page_size': 101},
    {'page_size': 'ten'},
])
def test_invalid_pages_rejected(admin_user, params):
    response = _get(admin_user, **params)
    assert response.status_code == 400
    assert response.data['errors']['code'] == 'invalid_query'


# --- filters & search ---------------------------------------------------------

def test_status_filter_uses_backend_values(admin_user, make_user, make_method, make_order):
    member = _member(make_user, make_method)
    make_order(member)
    cancelled = make_order(member, status=Order.Status.CANCELLED)

    data = _data(admin_user, status='CANCELLED')
    assert [o['id'] for o in data['orders']] == [cancelled.pk]
    assert data['total'] == 1


@pytest.mark.parametrize('value', ['PAID', 'cancelled', 'Active'])
def test_unknown_status_rejected(admin_user, value):
    response = _get(admin_user, status=value)
    assert response.status_code == 400
    assert response.data['errors']['code'] == 'invalid_query'


def test_search_by_reference_name_and_mobile(admin_user, make_user, make_method, make_order):
    asha = _member(make_user, make_method, name='Asha Kumar')
    ravi = _member(make_user, make_method, name='Ravi Shah')
    asha_order = make_order(asha)
    ravi_order = make_order(ravi)

    by_reference = _data(admin_user, search=asha_order.reference.lower())
    assert [o['id'] for o in by_reference['orders']] == [asha_order.pk]

    by_name = _data(admin_user, search='ravi')
    assert [o['id'] for o in by_name['orders']] == [ravi_order.pk]

    by_mobile = _data(admin_user, search=ravi.mobile_number[-6:])
    assert [o['id'] for o in by_mobile['orders']] == [ravi_order.pk]

    assert _data(admin_user, search='nobody')['orders'] == []


def test_search_too_long_rejected(admin_user):
    response = _get(admin_user, search='x' * 65)
    assert response.status_code == 400
    assert response.data['errors']['code'] == 'invalid_query'


def test_awaiting_action_filter(admin_user, make_user, make_method, make_order):
    member = _member(make_user, make_method)
    waiting = {
        make_order(member, status=status).pk
        for status in (Order.Status.PAYMENT_UNDER_REVIEW, Order.Status.ACTIVE, Order.Status.PAYOUT_IN_PROGRESS)
    }
    # (COMPLETED needs fully paid totals by DB constraint; the other final states stand in for it.)
    for status in (Order.Status.PAYMENT_REQUIRED, Order.Status.EXPIRED, Order.Status.CANCELLED):
        make_order(member, status=status)

    data = _data(admin_user, awaiting_action='true')
    assert {o['id'] for o in data['orders']} == waiting
    assert data['total'] == 3
    assert _data(admin_user, awaiting_action='false')['total'] == 6
    assert _get(admin_user, awaiting_action='yes').status_code == 400
