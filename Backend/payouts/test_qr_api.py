"""API tests for the admin payout QR endpoint (private, audited)."""
from decimal import Decimal

import pytest
from rest_framework.test import APIClient

from audit.models import AuditEvent
from conftest import buy_method_for, next_key
from core.models import User
from payins.services import confirm_pay_in, get_pay_in_intent, submit_pay_in_utr
from paymethods.storage import private_storage
from payouts.services import create_payout

QR_NAME = 'payout_qr/test.png'
# Not a decodable image: the endpoint serves stored bytes as-is.
QR_BYTES = b'\x89PNG\r\n\x1a\nstored-qr-bytes'


@pytest.fixture
def private_root(tmp_path, monkeypatch):
    """Point private storage at a temp dir so tests never touch private_media/."""
    monkeypatch.setattr(private_storage, 'base_location', str(tmp_path))
    monkeypatch.setattr(private_storage, 'location', str(tmp_path))
    return tmp_path


@pytest.fixture
def member(make_user, make_method):
    user = make_user()
    make_method(user, 'member@ybl', is_default=True, qr_image=QR_NAME)
    return user


@pytest.fixture
def payout(member, make_order, receiving_account, admin_user):
    order = make_order(member)
    intent = get_pay_in_intent(user=member, order_id=order.pk)
    payin = submit_pay_in_utr(
        user=member, order_id=order.pk, utr='555555555555',
        idempotency_key=next_key(), intent_token=intent.intent_token,
        buy_method_id=buy_method_for(member).pk,
    )
    confirm_pay_in(admin=admin_user, payin_id=payin.pk, idempotency_key=next_key())
    return create_payout(
        admin=admin_user, order_id=order.pk, amount=Decimal('100'), idempotency_key=next_key()
    )


@pytest.fixture
def stored_qr(private_root):
    path = private_root / QR_NAME
    path.parent.mkdir(parents=True)
    path.write_bytes(QR_BYTES)
    return path


@pytest.fixture
def super_admin(make_user):
    return make_user(role=User.Role.SUPER_ADMIN)


def _url(payout):
    return f'/api/admin/payouts/{payout.pk}/payout-method/qr/'


def _get(user, payout):
    client = APIClient()
    if user is not None:
        client.force_authenticate(user)
    return client.get(_url(payout))


def _qr_events():
    return AuditEvent.objects.filter(action=AuditEvent.Action.PAYOUT_METHOD_QR_VIEWED)


def _body(response):
    try:
        return b''.join(response.streaming_content)
    finally:
        # Release the file handle (Windows cannot clean up an open temp file).
        response.close()


# 1, 2, 7. authorized admins get the image; one audit event per view

@pytest.mark.parametrize('viewer', ['admin_user', 'super_admin'])
def test_admin_retrieves_qr_image(request, payout, stored_qr, viewer):
    admin = request.getfixturevalue(viewer)

    response = _get(admin, payout)

    assert response.status_code == 200
    assert response['Content-Type'] == 'image/png'
    assert response['Cache-Control'] == 'no-store'
    assert _body(response) == QR_BYTES

    event = _qr_events().get()
    assert event.actor == admin
    assert event.target_type == 'paymethods.UserPayoutMethod'
    assert event.target_id == str(payout.payout_method_id)
    assert event.metadata == {'payout_id': payout.pk, 'order_id': payout.order_id}


# 6. no filesystem path or storage URL in the response

def test_response_exposes_no_path_or_url(admin_user, payout, stored_qr, private_root):
    response = _get(admin_user, payout)
    body = _body(response)

    headers = ' '.join(f'{name}: {value}' for name, value in response.items())
    for leak in (str(private_root), str(stored_qr), QR_NAME, 'private_media', 'http'):
        assert leak not in headers
        assert leak.encode() not in body
    assert 'Location' not in response
    # The audit metadata stays identifiers-only as well.
    assert QR_NAME not in str(_qr_events().get().metadata)


# 3, 4, 8. non-admins are refused and nothing is audited

def test_member_cannot_retrieve_qr(member, payout, stored_qr):
    response = _get(member, payout)

    assert response.status_code == 403
    assert response.data['success'] is False
    assert not _qr_events().exists()


def test_anonymous_cannot_retrieve_qr(payout, stored_qr):
    response = _get(None, payout)

    assert response.status_code == 401
    assert response.data['success'] is False
    assert not _qr_events().exists()


# 5, 8. missing file

def test_missing_qr_file_returns_404_without_audit(admin_user, payout, private_root):
    response = _get(admin_user, payout)

    assert response.status_code == 404
    assert response.data['success'] is False
    assert response.data['errors']['code'] == 'payout_qr_not_found'
    assert not _qr_events().exists()
