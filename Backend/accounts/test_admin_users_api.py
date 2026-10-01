"""API tests for GET /api/admin/users/ and /api/admin/users/{id}/ (admin member directory, read-only)."""
import pytest
from rest_framework.test import APIClient

from core.models import User
from orders.models import Order

URL = '/api/admin/users/'


@pytest.fixture
def super_admin(make_user):
    return make_user(role=User.Role.SUPER_ADMIN)


def _client(user):
    client = APIClient()
    client.force_authenticate(user)
    return client


def _data(user, url=URL, **params):
    response = _client(user).get(url, params)
    assert response.status_code == 200, response.data
    return response.data['data']


def _named(make_user, name, **extra):
    user = make_user(**extra)
    user.name = name
    user.save(update_fields=['name'])
    return user


# --- access -------------------------------------------------------------------

def test_admin_and_super_admin_can_list(admin_user, super_admin):
    for viewer in (admin_user, super_admin):
        assert set(_data(viewer)) == {'users', 'page', 'page_size', 'total', 'has_next'}


def test_member_forbidden_and_anonymous_rejected(make_user):
    member = make_user()
    for url in (URL, f'{URL}{member.pk}/'):
        assert _client(member).get(url).status_code == 403
        assert APIClient().get(url).status_code == 401


def test_inactive_admin_forbidden(make_user):
    assert _client(make_user(role=User.Role.ADMIN, is_active=False)).get(URL).status_code == 403


# --- list ---------------------------------------------------------------------

def test_lists_members_only_with_masked_mobile_and_no_secrets(admin_user, super_admin, make_user):
    member = _named(make_user, 'Asha Kumar')

    users = _data(admin_user)['users']
    assert [u['id'] for u in users] == [member.pk]
    item = users[0]
    assert set(item) == {'id', 'name', 'mobile_number_masked', 'role', 'is_verified', 'is_active', 'date_joined'}
    assert item['mobile_number_masked'] == '******' + member.mobile_number[-4:]
    assert member.mobile_number not in str(item)


def test_filters(admin_user, make_user):
    verified = make_user()
    unverified = make_user(is_verified=False)
    inactive = make_user(is_active=False)

    def ids(**params):
        return {u['id'] for u in _data(admin_user, **params)['users']}

    assert ids() == {verified.pk, unverified.pk, inactive.pk}
    assert ids(is_active='true') == {verified.pk, unverified.pk}
    assert ids(is_active='false') == {inactive.pk}
    assert ids(is_verified='true') == {verified.pk, inactive.pk}
    assert ids(is_verified='FALSE') == {unverified.pk}


@pytest.mark.parametrize('params', [
    {'is_active': 'yes'},
    {'is_verified': '1'},
    {'page': 2},
    {'page_size': 0},
    {'search': 'x' * 65},
])
def test_invalid_queries_rejected(admin_user, params):
    response = _client(admin_user).get(URL, params)
    assert response.status_code == 400
    assert response.data['errors']['code'] == 'invalid_query'


def test_search_by_name_and_mobile(admin_user, make_user):
    asha = _named(make_user, 'Asha Kumar')
    ravi = _named(make_user, 'Ravi Shah')

    assert [u['id'] for u in _data(admin_user, search='kumar')['users']] == [asha.pk]
    assert [u['id'] for u in _data(admin_user, search=ravi.mobile_number[-5:])['users']] == [ravi.pk]
    assert _data(admin_user, search='zzz')['users'] == []


def test_pagination_newest_first(admin_user, make_user):
    created = [make_user() for _ in range(3)]
    first = _data(admin_user, page_size=2)
    assert [u['id'] for u in first['users']] == [created[2].pk, created[1].pk]
    assert (first['total'], first['has_next']) == (3, True)
    assert [u['id'] for u in _data(admin_user, page_size=2, page=2)['users']] == [created[0].pk]


# --- detail -------------------------------------------------------------------

def test_detail_counts_activity_on_the_server(admin_user, make_user, make_method, make_order):
    member = _named(make_user, 'Asha Kumar')
    make_method(member, 'asha@ybl', is_default=True)
    make_order(member)
    make_order(member, status=Order.Status.CANCELLED)
    make_order(make_user())  # someone else's order

    data = _data(admin_user, f'{URL}{member.pk}/')
    assert data['user']['id'] == member.pk
    assert data['user']['mobile_number_masked'].endswith(member.mobile_number[-4:])
    assert member.mobile_number not in str(data)
    assert 'password' not in str(data)
    assert data['orders_total'] == 2
    assert data['order_counts']['PAYMENT_REQUIRED'] == 1
    assert data['order_counts']['CANCELLED'] == 1
    assert set(data['order_counts']) == set(Order.Status.values)
    assert set(data['pay_in_counts']) == {'UNDER_REVIEW', 'CONFIRMED', 'REJECTED'}
    assert len(data['recent_orders']) == 2
    assert data['recent_orders'][0]['payout_target'] == '265.00'


def test_detail_hides_non_members_and_unknown_ids(admin_user, super_admin):
    for user_id in (admin_user.pk, super_admin.pk, 999999):
        response = _client(admin_user).get(f'{URL}{user_id}/')
        assert response.status_code == 404
        assert response.data['errors']['code'] == 'user_not_found'
