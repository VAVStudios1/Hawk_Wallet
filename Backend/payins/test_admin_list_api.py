"""API tests for GET /api/admin/pay-ins/ and /api/admin/pay-ins/{id}/ (admin review list and detail)."""
import pytest
from rest_framework.test import APIClient

from audit.models import AuditEvent, LedgerEntry
from conftest import buy_method_for, next_key
from core.models import User
from payins.models import PayIn
from payins.services import confirm_pay_in, get_pay_in_intent, reject_pay_in, submit_pay_in_utr

URL = '/api/admin/pay-ins/'
_utrs = iter(range(300000000000, 999999999999))


@pytest.fixture
def super_admin(make_user):
    return make_user(role=User.Role.SUPER_ADMIN)


@pytest.fixture
def member(make_user, make_method):
    user = _named(make_user, 'Asha Kumar')
    make_method(user, 'asha@ybl', is_default=True)
    return user


def _client(user):
    client = APIClient()
    client.force_authenticate(user)
    return client


def _data(user, url=URL, **params):
    response = _client(user).get(url, params)
    assert response.status_code == 200, response.data
    return response.data['data']


def _submit(member, order):
    intent = get_pay_in_intent(user=member, order_id=order.pk)
    return submit_pay_in_utr(
        user=member, order_id=order.pk, utr=str(next(_utrs)),
        idempotency_key=next_key(), intent_token=intent.intent_token,
        buy_method_id=buy_method_for(member).pk,
    )


def _named(make_user, name, **extra):
    user = make_user(**extra)
    user.name = name
    user.save(update_fields=['name'])
    return user


# --- access -------------------------------------------------------------------

def test_admin_and_super_admin_can_list(admin_user, super_admin):
    for viewer in (admin_user, super_admin):
        assert set(_data(viewer)) == {'pay_ins', 'page', 'page_size', 'total', 'has_next'}


def test_member_forbidden_and_anonymous_rejected(member, make_order, receiving_account):
    payin = _submit(member, make_order(member))
    for url in (URL, f'{URL}{payin.pk}/'):
        assert _client(member).get(url).status_code == 403
        assert APIClient().get(url).status_code == 401


# --- list ---------------------------------------------------------------------

def test_list_fields_mask_mobile_and_hide_buy_method(member, admin_user, make_order, receiving_account):
    order = make_order(member)
    payin = _submit(member, order)

    item = _data(admin_user)['pay_ins'][0]
    assert item['id'] == payin.pk
    assert item['status'] == 'UNDER_REVIEW'
    assert item['amount'] == '255.00'
    assert item['utr'] == payin.utr
    assert item['order'] == {
        'id': order.pk, 'reference': order.reference, 'status': 'PAYMENT_UNDER_REVIEW',
        'order_amount': '255.00', 'payout_target': '265.00',
    }
    assert item['member']['name'] == 'Asha Kumar'
    assert item['member']['mobile_number_masked'] == '******' + member.mobile_number[-4:]
    assert member.mobile_number not in str(item)
    assert 'buy_method' not in item
    # Listing is read-only and unaudited.
    assert AuditEvent.objects.filter(action=AuditEvent.Action.BUY_METHOD_VIEWED).count() == 0


def test_status_filter_search_and_ordering(member, admin_user, make_order, receiving_account):
    first = _submit(member, make_order(member))
    second = _submit(member, make_order(member))
    reject_pay_in(admin=admin_user, payin_id=first.pk, rejection_reason='No credit', idempotency_key=next_key())

    assert [p['id'] for p in _data(admin_user, status='UNDER_REVIEW')['pay_ins']] == [second.pk]
    assert [p['id'] for p in _data(admin_user, status='REJECTED')['pay_ins']] == [first.pk]
    assert [p['id'] for p in _data(admin_user)['pay_ins']] == [second.pk, first.pk]
    assert [p['id'] for p in _data(admin_user, ordering='submitted_at')['pay_ins']] == [first.pk, second.pk]
    assert [p['id'] for p in _data(admin_user, search=second.utr)['pay_ins']] == [second.pk]
    assert [p['id'] for p in _data(admin_user, search=first.order.reference)['pay_ins']] == [first.pk]
    assert _data(admin_user, search='asha')['total'] == 2


@pytest.mark.parametrize('params', [
    {'status': 'PENDING'},
    {'ordering': 'amount'},
    {'page': 2},
    {'page_size': 500},
])
def test_invalid_queries_rejected(admin_user, params):
    response = _client(admin_user).get(URL, params)
    assert response.status_code == 400
    assert response.data['errors']['code'] == 'invalid_query'


def test_pagination(member, admin_user, make_order, receiving_account):
    for _ in range(3):
        _submit(member, make_order(member))
    data = _data(admin_user, page_size=2, page=2)
    assert len(data['pay_ins']) == 1
    assert (data['total'], data['has_next']) == (3, False)


# --- detail -------------------------------------------------------------------

def test_detail_shows_review_fields_and_audits_buy_method(member, admin_user, make_order, receiving_account):
    payin = _submit(member, make_order(member))

    data = _data(admin_user, f'{URL}{payin.pk}/')
    assert data['payee_vpa'] == payin.payee_vpa_snapshot
    assert data['buy_method']['upi_id'] == payin.buy_method.upi_id
    assert data['rejection_reason'] == ''
    assert data['reviewed_by_name'] is None
    events = AuditEvent.objects.filter(action=AuditEvent.Action.BUY_METHOD_VIEWED, actor=admin_user)
    assert events.count() == 1
    assert LedgerEntry.objects.filter(action=LedgerEntry.Action.PAYIN_CONFIRMED).count() == 0


def test_detail_after_review_names_reviewer(member, admin_user, make_order, receiving_account):
    payin = _submit(member, make_order(member))
    confirm_pay_in(admin=admin_user, payin_id=payin.pk, idempotency_key=next_key())
    data = _data(admin_user, f'{URL}{payin.pk}/')
    assert data['status'] == 'CONFIRMED'
    assert data['reviewed_by_name'] == admin_user.name
    assert data['order']['status'] == 'ACTIVE'


def test_detail_unknown_is_404(admin_user):
    response = _client(admin_user).get(f'{URL}999999/')
    assert response.status_code == 404
    assert response.data['errors']['code'] == 'payin_not_found'


def test_existing_confirm_endpoint_still_works(member, super_admin, make_order, receiving_account):
    payin = _submit(member, make_order(member))
    response = _client(super_admin).post(
        f'{URL}{payin.pk}/confirm/', {'idempotency_key': next_key()}, format='json'
    )
    assert response.status_code == 200, response.data
    payin.refresh_from_db()
    assert payin.status == PayIn.Status.CONFIRMED
