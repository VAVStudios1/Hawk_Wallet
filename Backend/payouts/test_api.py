"""Smoke tests for the admin pay-in review and payout endpoints."""
import pytest
from rest_framework.test import APIClient

from audit.models import AuditEvent
from conftest import buy_method_for, next_key
from core.models import User
from orders.models import Order
from payins.services import get_pay_in_intent, submit_pay_in_utr
from payouts.models import Payout


@pytest.fixture
def member(make_user, make_method):
    user = make_user()
    make_method(user, 'member@ybl', is_default=True)
    return user


@pytest.fixture
def admin_client(admin_user):
    client = APIClient()
    client.force_authenticate(admin_user)
    return client


@pytest.fixture
def submitted(member, make_order, receiving_account):
    order = make_order(member)
    intent = get_pay_in_intent(user=member, order_id=order.pk)
    return submit_pay_in_utr(
        user=member, order_id=order.pk, utr='555555555555',
        idempotency_key=next_key(), intent_token=intent.intent_token,
        buy_method_id=buy_method_for(member).pk,
    )


def _post(client, url, data):
    return client.post(url, data, format='json')


def _confirm(client, payin):
    return _post(client, f'/api/admin/pay-ins/{payin.pk}/confirm/', {'idempotency_key': next_key()})


def test_admin_confirms_pay_in(admin_client, submitted):
    response = _post(
        admin_client, f'/api/admin/pay-ins/{submitted.pk}/confirm/',
        {'idempotency_key': next_key(), 'status': 'REJECTED', 'amount': '1'},
    )
    assert response.status_code == 200, response.data
    body = response.data['data']
    assert body['status'] == 'CONFIRMED'
    assert body['order_status'] == Order.Status.ACTIVE
    assert body['amount'] == '255.00'


def test_admin_rejects_pay_in(admin_client, submitted):
    response = _post(
        admin_client, f'/api/admin/pay-ins/{submitted.pk}/reject/',
        {'idempotency_key': next_key(), 'rejection_reason': 'UTR not found'},
    )
    assert response.status_code == 200, response.data
    body = response.data['data']
    assert body['status'] == 'REJECTED'
    assert body['rejection_reason'] == 'UTR not found'
    assert body['order_status'] == Order.Status.PAYMENT_REQUIRED


def test_create_pay_and_view_payout(admin_client, admin_user, submitted):
    _confirm(admin_client, submitted)
    order_id = submitted.order_id

    response = _post(
        admin_client, f'/api/admin/orders/{order_id}/payouts/',
        {'amount': '265', 'idempotency_key': next_key(), 'upi_id': 'evil@ybl', 'user': 1},
    )
    assert response.status_code == 201, response.data
    payout = response.data['data']
    assert payout['status'] == Payout.Status.PENDING
    assert payout['order_status'] == Order.Status.PAYOUT_IN_PROGRESS
    assert payout['order_payout_committed_total'] == '265.00'
    assert Payout.objects.get().upi_id_snapshot == 'member@ybl'

    response = admin_client.get(f"/api/admin/payouts/{payout['id']}/payout-method/")
    assert response.status_code == 200, response.data
    view = response.data['data']
    assert view['upi_id'] == 'member@ybl' and view['provider_code'] == 'PHONEPE'
    assert 'url' not in str(view)
    assert AuditEvent.objects.filter(action=AuditEvent.Action.PAYOUT_METHOD_VIEWED).count() == 1

    response = _post(
        admin_client, f"/api/admin/payouts/{payout['id']}/paid/",
        {'utr': '012345678901', 'idempotency_key': next_key(), 'amount': '1'},
    )
    assert response.status_code == 200, response.data
    paid = response.data['data']
    assert paid['status'] == Payout.Status.PAID
    assert paid['utr'] == '012345678901'
    assert paid['paid_by'] == admin_user.pk
    assert paid['order_status'] == Order.Status.COMPLETED
    assert paid['order_payout_paid_total'] == '265.00'


@pytest.mark.parametrize('extra', [
    {},                                             # member
    {'role': User.Role.ADMIN, 'is_active': False},
])
def test_non_admin_forbidden(make_user, submitted, extra):
    client = APIClient()
    client.force_authenticate(make_user(**extra))
    response = _confirm(client, submitted)
    assert response.status_code == 403
    assert response.data['success'] is False
    submitted.refresh_from_db()
    assert submitted.status == 'UNDER_REVIEW'


def test_invalid_input(admin_client, submitted):
    _confirm(admin_client, submitted)
    response = _post(
        admin_client, f'/api/admin/orders/{submitted.order_id}/payouts/',
        {'amount': '100.50', 'idempotency_key': next_key()},
    )
    assert response.status_code == 400
    assert response.data['errors']['code'] == 'invalid_amount'

    response = _post(admin_client, '/api/admin/payouts/999999/paid/',
                     {'utr': '012345678901', 'idempotency_key': next_key()})
    assert response.status_code == 404
    assert not Payout.objects.exists()


def test_partial_payouts_cancel_and_order_detail_history(admin_client, admin_user, submitted):
    _confirm(admin_client, submitted)
    order_id = submitted.order_id
    create_url = f'/api/admin/orders/{order_id}/payouts/'

    ids = []
    for amount in ('100', '150'):
        response = _post(admin_client, create_url, {'amount': amount, 'idempotency_key': next_key()})
        assert response.status_code == 201, response.data
        ids.append(response.data['data']['id'])
    paid = _post(admin_client, f'/api/admin/payouts/{ids[0]}/paid/',
                 {'utr': '012345678901', 'idempotency_key': next_key()})
    assert paid.status_code == 200, paid.data

    # Remaining 15 fits, 16 would exceed the 265 target.
    over = _post(admin_client, create_url, {'amount': '16', 'idempotency_key': next_key()})
    assert over.status_code == 409
    assert over.data['errors']['code'] == 'payout_exceeds_remaining'

    cancel_url = f'/api/admin/payouts/{ids[1]}/cancel/'
    body = {'cancellation_reason': 'Wrong amount', 'idempotency_key': next_key()}
    response = _post(admin_client, cancel_url, body)
    assert response.status_code == 200, response.data
    assert response.data['data']['status'] == Payout.Status.CANCELLED
    assert response.data['data']['order_payout_committed_total'] == '100.00'

    replay = _post(admin_client, cancel_url, body)
    assert replay.status_code == 200, replay.data
    different = _post(admin_client, cancel_url,
                      {'cancellation_reason': 'Other', 'idempotency_key': next_key()})
    assert different.status_code == 409
    assert different.data['errors']['code'] == 'payout_already_cancelled'
    paid_cancel = _post(admin_client, f'/api/admin/payouts/{ids[0]}/cancel/',
                        {'cancellation_reason': 'Oops', 'idempotency_key': next_key()})
    assert paid_cancel.status_code == 409
    assert paid_cancel.data['errors']['code'] == 'payout_not_cancellable'
    blank = _post(admin_client, cancel_url, {'cancellation_reason': ' ', 'idempotency_key': next_key()})
    assert blank.status_code == 400
    assert blank.data['errors']['code'] == 'invalid_cancellation_reason'

    detail = admin_client.get(f'/api/admin/orders/{order_id}/').data['data']
    assert detail['payout_target'] == '265.00'
    assert detail['payout_committed_total'] == '100.00'
    assert detail['remaining_to_commit'] == '165.00'
    history = detail['payouts']
    assert [p['id'] for p in history] == ids
    assert set(history[0]) == {
        'id', 'amount', 'status', 'utr', 'created_by', 'paid_by', 'created_at',
        'paid_at', 'provider_code', 'provider_name', 'upi_id', 'cancellation_reason',
    }
    assert history[0]['status'] == Payout.Status.PAID
    assert history[0]['utr'] == '012345678901'
    assert history[0]['paid_by'] == admin_user.pk
    assert history[0]['upi_id'] == 'member@ybl' and history[0]['provider_code'] == 'PHONEPE'
    assert history[1]['status'] == Payout.Status.CANCELLED
    assert history[1]['amount'] == '150.00'
    assert history[1]['cancellation_reason'] == 'Wrong amount'
    assert history[1]['created_by'] == admin_user.pk
    assert history[1]['paid_by'] is None and history[1]['paid_at'] is None
    # The QR stays private: no storage path or URL in the detail.
    assert 'payout_qr' not in str(detail) and 'qr' not in str(history)
