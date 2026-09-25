import threading
from decimal import Decimal
from urllib.parse import parse_qsl, urlsplit

import pytest
from django.contrib.auth.models import AnonymousUser
from django.core import signing
from django.db import connection
from django.utils import timezone

from audit.models import AuditEvent, LedgerEntry
from core.models import User
from orders.models import Order
from payins.exceptions import (
    IdempotencyKeyConflict,
    InvalidIdempotencyKey,
    InvalidPayInIntent,
    InvalidRejectionReason,
    InvalidUtr,
    NoReceivingAccount,
    OrderNotPayable,
    PayerAppUnavailable,
    PayInAlreadyReviewed,
    PayInConflict,
    PayInNotFound,
    PayInOrderNotFound,
    PayInPermissionDenied,
    ReviewPermissionDenied,
    UtrAlreadySubmitted,
)
from payins import services
from payins.models import PayIn
from payins.services import (
    build_upi_pay_uri,
    confirm_pay_in,
    get_pay_in_intent,
    reject_pay_in,
    submit_pay_in_utr,
)
from paymethods.models import PaymentProvider, PlatformReceivingAccount
from payouts.models import Payout

from conftest import next_key


@pytest.fixture
def member(make_user, make_method):
    user = make_user()
    make_method(user, 'member.payout@ybl', is_default=True)
    return user


@pytest.fixture
def order(member, make_order):
    return make_order(member)


def _intent(user, order):
    return get_pay_in_intent(user=user, order_id=order.pk)


def _query(uri):
    parts = urlsplit(uri)
    assert (parts.scheme, parts.netloc) == ('upi', 'pay')
    return parts.query, dict(parse_qsl(parts.query, strict_parsing=True))


def _account(provider, **values):
    defaults = dict(
        provider=provider,
        payee_vpa='other@ybl',
        payee_name='Other',
        is_active=True,
    )
    defaults.update(values)
    return PlatformReceivingAccount.objects.create(**defaults)


# --- A. successful intent -----------------------------------------------------

def test_intent_for_payable_order(member, order, receiving_account):
    intent = _intent(member, order)

    assert intent.order_reference == order.reference
    assert intent.amount == Decimal('255.00')
    assert isinstance(intent.amount, Decimal)
    assert intent.currency == 'INR'
    assert intent.receiving_account_id == receiving_account.pk
    assert intent.payee_vpa == 'platform@ybl'
    assert intent.payee_name == 'Hawk Wallet'
    assert intent.upi_uri == (
        f'upi://pay?pa=platform@ybl&pn=Hawk%20Wallet&tr={order.reference}'
        f'&am=255.00&cu=INR&tn=Hawk%20Wallet%20order%20{order.reference}'
    )


def test_amount_is_order_amount_not_income_or_payout_target(member, order, receiving_account):
    intent = _intent(member, order)

    assert intent.amount == order.order_amount
    assert intent.amount != order.income_amount
    assert intent.amount != order.payout_target
    _, params = _query(intent.upi_uri)
    assert params['am'] == '255.00'


def test_order_id_may_be_passed_as_string(member, order, receiving_account):
    assert get_pay_in_intent(user=member, order_id=str(order.pk)).order_reference == order.reference


# --- B. no side effects, repeatable -------------------------------------------

def test_intent_has_no_side_effects(member, order, receiving_account):
    before = Order.objects.values().get(pk=order.pk)

    for _ in range(3):
        _intent(member, order)

    assert Order.objects.values().get(pk=order.pk) == before
    assert not PayIn.objects.exists()
    assert not LedgerEntry.objects.exists()
    assert not AuditEvent.objects.exists()


def test_repeated_calls_return_same_intent(member, order, receiving_account):
    assert _intent(member, order) == _intent(member, order)


def test_uri_carries_order_reference_as_tr_and_in_note(member, order, receiving_account):
    assert order.reference.startswith('HW-')
    _, params = _query(_intent(member, order).upi_uri)

    assert params['tr'] == order.reference
    assert params['tn'] == f'Hawk Wallet order {order.reference}'


# --- C. privacy ---------------------------------------------------------------

def test_intent_never_exposes_member_payout_method(member, order, receiving_account):
    intent = _intent(member, order)
    payout_upi = order.payout_method.upi_id

    for value in vars(intent).values():
        assert payout_upi not in str(value)
        assert order.payout_method.qr_image.name not in str(value)
    assert 'qr' not in {name.lower() for name in vars(intent)}


# --- D. URI encoding ----------------------------------------------------------

def test_uri_percent_encodes_payee_name(member, order, provider):
    _account(provider, payee_vpa='hawk.pay-1@icici', payee_name='Hawk & Co / Pay=100% +1')

    intent = _intent(member, order)
    raw, params = _query(intent.upi_uri)

    assert 'pn=Hawk%20%26%20Co%20%2F%20Pay%3D100%25%20%2B1' in raw
    assert '+' not in raw
    assert params['pa'] == 'hawk.pay-1@icici'
    assert params['pn'] == 'Hawk & Co / Pay=100% +1'
    assert params['am'] == '255.00'
    assert params['cu'] == 'INR'
    assert params['tr'] == order.reference
    assert params['tn'] == f'Hawk Wallet order {order.reference}'


def test_uri_includes_merchant_code_only_when_set(member, order, provider):
    account = _account(provider, merchant_code='5411')

    _, params = _query(_intent(member, order).upi_uri)
    assert params['mc'] == '5411'

    account.merchant_code = ''
    account.save()
    _, params = _query(_intent(member, order).upi_uri)
    assert 'mc' not in params


def test_uri_formats_amount_with_two_decimals(receiving_account):
    uri = build_upi_pay_uri(
        account=receiving_account, amount=Decimal('1000'), currency='INR', reference='HW-X'
    )
    assert _query(uri)[1]['am'] == '1000.00'


def test_uri_rejects_non_decimal_amount(receiving_account):
    with pytest.raises(TypeError):
        build_upi_pay_uri(account=receiving_account, amount=255.0, currency='INR', reference='HW-X')


# --- E. permissions and ownership ---------------------------------------------

@pytest.mark.parametrize('extra', [
    {'is_verified': False},
    {'is_active': False},
    {'role': User.Role.ADMIN},
    {'role': User.Role.SUPER_ADMIN},
])
def test_non_member_or_unverified_user_rejected(make_user, make_order, receiving_account, extra):
    user = make_user(**extra)
    order = make_order(user)
    with pytest.raises(PayInPermissionDenied):
        _intent(user, order)


def test_anonymous_user_rejected(order, receiving_account):
    with pytest.raises(PayInPermissionDenied):
        _intent(AnonymousUser(), order)


def test_other_members_order_is_not_found(make_user, order, receiving_account):
    with pytest.raises(PayInOrderNotFound):
        _intent(make_user(), order)


@pytest.mark.parametrize('order_id', [999999, 'abc', None, ''])
def test_unknown_order_is_not_found(member, receiving_account, order_id):
    with pytest.raises(PayInOrderNotFound):
        get_pay_in_intent(user=member, order_id=order_id)


# --- F. order status ----------------------------------------------------------

@pytest.mark.parametrize('status', [
    s for s in Order.Status.values if s != Order.Status.PAYMENT_REQUIRED
])
def test_order_not_awaiting_payment_rejected(member, order, receiving_account, status):
    changes = {'status': status}
    if status == Order.Status.COMPLETED:
        changes.update(
            payout_committed_total=order.payout_target,
            payout_paid_total=order.payout_target,
        )
    Order.objects.filter(pk=order.pk).update(**changes)

    with pytest.raises(OrderNotPayable):
        _intent(member, order)
    assert not PayIn.objects.exists()
    assert Order.objects.get(pk=order.pk).status == status


# --- G. receiving account selection -------------------------------------------

def test_no_receiving_account(member, order):
    with pytest.raises(NoReceivingAccount):
        _intent(member, order)


def test_inactive_account_is_skipped(member, order, provider):
    _account(provider, payee_vpa='off@ybl', is_active=False)
    with pytest.raises(NoReceivingAccount):
        _intent(member, order)


def test_account_with_unusable_provider_is_skipped(member, order, provider):
    no_pay_in = PaymentProvider.objects.create(
        code='GPAY', display_name='Google Pay', enabled_for_pay_in=False
    )
    disabled = PaymentProvider.objects.create(
        code='PAYTM', display_name='Paytm', enabled_for_pay_in=True, is_active=False
    )
    _account(no_pay_in, payee_vpa='nopayin@okaxis', priority=0)
    _account(disabled, payee_vpa='disabled@paytm', priority=0)
    usable = _account(provider, payee_vpa='usable@ybl', priority=5)

    assert _intent(member, order).receiving_account_id == usable.pk


def test_lowest_priority_then_lowest_id_wins(member, order, provider):
    _account(provider, payee_vpa='later@ybl', priority=2)
    first = _account(provider, payee_vpa='first@ybl', priority=1)
    _account(provider, payee_vpa='tie@ybl', priority=1)

    intent = _intent(member, order)
    assert intent.receiving_account_id == first.pk
    assert intent.payee_vpa == 'first@ybl'


# =============================================================================
# submit_pay_in_utr
# =============================================================================

UTR = '123456789012'


def _token_for(user, order, account=None):
    # A server-signed token as get_pay_in_intent would issue, for tests
    # where the intent itself cannot be fetched (wrong user, wrong status).
    account = account or PlatformReceivingAccount.objects.order_by('priority', 'id').first()
    return services._sign_intent(user, order, account)


def _submit(user, order, utr=UTR, key=None, intent_token=None, **kwargs):
    return submit_pay_in_utr(
        user=user,
        order_id=order.pk,
        utr=utr,
        idempotency_key=key or next_key(),
        intent_token=intent_token or _token_for(user, order),
        **kwargs,
    )


def _submitted_ledger(order=None):
    entries = LedgerEntry.objects.filter(action=LedgerEntry.Action.PAYIN_SUBMITTED)
    return entries.filter(order=order) if order else entries


def _admin_reject(payin):
    # Stand-in for the (not yet built) admin review service.
    payin.status = PayIn.Status.REJECTED
    payin.reviewed_at = timezone.now()
    payin.rejection_reason = 'UTR not found'
    payin.save()
    Order.objects.filter(pk=payin.order_id).update(status=Order.Status.PAYMENT_REQUIRED)


def _assert_nothing_written(order):
    assert not PayIn.objects.exists()
    assert not LedgerEntry.objects.exists()
    assert Order.objects.get(pk=order.pk).status == Order.Status.PAYMENT_REQUIRED


def _assert_nothing_written_for(order):
    assert not order.pay_ins.exists()
    assert not _submitted_ledger(order).exists()
    assert Order.objects.get(pk=order.pk).status == Order.Status.PAYMENT_REQUIRED


# --- H. successful submission -------------------------------------------------

def test_submit_valid_utr(member, order, receiving_account):
    payin = _submit(member, order, key='submit-1')

    payin.refresh_from_db()
    assert payin.order == order
    assert payin.user == member == order.user
    assert payin.amount == order.order_amount == Decimal('255.00')
    assert payin.utr == UTR
    assert payin.status == PayIn.Status.UNDER_REVIEW
    assert payin.receiving_account == receiving_account
    assert payin.payee_vpa_snapshot == 'platform@ybl'
    assert payin.payer_app is None
    assert payin.idempotency_key == 'submit-1'
    assert payin.reviewed_by is None and payin.reviewed_at is None


def test_submit_amount_is_order_amount_not_income_or_payout_target(member, order, receiving_account):
    payin = _submit(member, order)
    assert payin.amount == order.order_amount
    assert payin.amount not in (order.income_amount, order.payout_target)


def test_submit_moves_order_to_under_review(member, order, receiving_account):
    before = Order.objects.values().get(pk=order.pk)
    _submit(member, order)

    after = Order.objects.values().get(pk=order.pk)
    assert after['status'] == Order.Status.PAYMENT_UNDER_REVIEW
    # Nothing else about the order's money or lifecycle moves.
    for field in ('order_amount', 'income_amount', 'payout_target', 'payout_method_id',
                  'payout_committed_total', 'payout_paid_total', 'activated_at', 'completed_at'):
        assert after[field] == before[field]


def test_submit_writes_one_payin_submitted_ledger_entry(member, order, receiving_account):
    payin = _submit(member, order)

    entry = LedgerEntry.objects.get()
    assert entry.action == LedgerEntry.Action.PAYIN_SUBMITTED
    assert entry.actor == member
    assert entry.order == order
    assert entry.payin == payin
    assert entry.amount == order.order_amount
    assert entry.balance_before is None and entry.balance_after is None
    assert entry.reference_id == order.reference


def test_submit_does_not_confirm_or_pay_out(member, order, receiving_account):
    _submit(member, order)

    assert not Payout.objects.exists()
    assert not PayIn.objects.filter(status=PayIn.Status.CONFIRMED).exists()
    assert set(LedgerEntry.objects.values_list('action', flat=True)) == {
        LedgerEntry.Action.PAYIN_SUBMITTED
    }
    order.refresh_from_db()
    assert order.status == Order.Status.PAYMENT_UNDER_REVIEW
    assert order.activated_at is None
    assert order.payout_committed_total == order.payout_paid_total == Decimal('0')


def test_leading_zero_utr_preserved_as_string(member, order, receiving_account):
    payin = _submit(member, order, utr='000012345678')
    payin.refresh_from_db()
    assert payin.utr == '000012345678'
    assert isinstance(payin.utr, str)


def test_submit_records_payer_app(member, order, receiving_account, provider):
    payin = _submit(member, order, payer_app=provider.pk)
    assert payin.payer_app == provider


# --- I. UTR and key validation ------------------------------------------------

@pytest.mark.parametrize('utr', [
    '12345678901',          # 11 digits
    '1234567890123',        # 13 digits
    '12345678901A',         # letter
    'ABCDEFGHIJKL',
    '123456 789012',        # inner space
    ' 123456789012',        # leading space
    '123456789012 ',        # trailing space
    '123456789012\n',       # trailing newline
    '1234-5678-9012',       # dashes
    '12345-678901',         # dash, 12 characters
    '+12345678901',
    '١٢٣٤٥٦٧٨٩٠١٢',  # non-ASCII digits
    '',
    123456789012,           # not a string
    None,
])
def test_invalid_utr_rejected(member, order, receiving_account, utr):
    with pytest.raises(InvalidUtr):
        _submit(member, order, utr=utr)
    _assert_nothing_written(order)


@pytest.mark.parametrize('key', ['', '   ', None, 'x' * 65])
def test_invalid_idempotency_key_rejected(member, order, receiving_account, key):
    with pytest.raises(InvalidIdempotencyKey):
        submit_pay_in_utr(
            user=member, order_id=order.pk, utr=UTR, idempotency_key=key,
            intent_token=_token_for(member, order),
        )
    _assert_nothing_written(order)


# --- J. permissions and ownership ---------------------------------------------

@pytest.mark.parametrize('extra', [
    {'is_verified': False},
    {'is_active': False},
    {'role': User.Role.ADMIN},
    {'role': User.Role.SUPER_ADMIN},
])
def test_submit_by_non_member_or_unverified_rejected(make_user, make_order, receiving_account, extra):
    user = make_user(**extra)
    order = make_order(user)
    with pytest.raises(PayInPermissionDenied):
        _submit(user, order)
    _assert_nothing_written(order)


def test_submit_by_anonymous_rejected(order, receiving_account):
    with pytest.raises(PayInPermissionDenied):
        _submit(AnonymousUser(), order)
    _assert_nothing_written(order)


def test_submit_for_other_members_order_is_not_found(make_user, order, receiving_account):
    with pytest.raises(PayInOrderNotFound):
        _submit(make_user(), order)
    _assert_nothing_written(order)


def test_submit_for_unknown_order_is_not_found(member, receiving_account):
    missing = Order(pk=999999)
    with pytest.raises(PayInOrderNotFound):
        _submit(member, missing)
    assert not PayIn.objects.exists()


# --- K. order status ----------------------------------------------------------

@pytest.mark.parametrize('status', [
    s for s in Order.Status.values if s != Order.Status.PAYMENT_REQUIRED
])
def test_submit_for_order_not_awaiting_payment_rejected(member, order, receiving_account, status):
    changes = {'status': status}
    if status == Order.Status.COMPLETED:
        changes.update(
            payout_committed_total=order.payout_target,
            payout_paid_total=order.payout_target,
        )
    Order.objects.filter(pk=order.pk).update(**changes)

    with pytest.raises(OrderNotPayable):
        _submit(member, order)
    assert not PayIn.objects.exists()
    assert not LedgerEntry.objects.exists()
    assert Order.objects.get(pk=order.pk).status == status


def test_second_submission_while_under_review_rejected(member, order, receiving_account):
    first = _submit(member, order, utr='111111111111')

    with pytest.raises(OrderNotPayable):
        _submit(member, order, utr='222222222222')
    assert list(PayIn.objects.all()) == [first]
    assert _submitted_ledger().count() == 1


def test_active_payin_blocks_submission_even_if_status_disagrees(member, order, receiving_account):
    _submit(member, order, utr='111111111111')
    # Force an inconsistent state: the order says PAYMENT_REQUIRED while a
    # PayIn is still under review.
    Order.objects.filter(pk=order.pk).update(status=Order.Status.PAYMENT_REQUIRED)

    with pytest.raises(OrderNotPayable):
        _submit(member, order, utr='222222222222')
    assert PayIn.objects.count() == 1


# --- L. resubmission after rejection ------------------------------------------

def test_resubmission_after_rejection_creates_new_payin(member, order, receiving_account):
    rejected = _submit(member, order, utr='111111111111')
    _admin_reject(rejected)
    snapshot = PayIn.objects.values().get(pk=rejected.pk)

    second = _submit(member, order, utr='222222222222')

    assert second.pk != rejected.pk
    assert second.status == PayIn.Status.UNDER_REVIEW
    assert second.utr == '222222222222'
    assert PayIn.objects.values().get(pk=rejected.pk) == snapshot
    assert order.pay_ins.count() == 2
    assert Order.objects.get(pk=order.pk).status == Order.Status.PAYMENT_UNDER_REVIEW
    assert _submitted_ledger(order).count() == 2


# --- M. UTR uniqueness (follows the PayIn constraints) ------------------------

def test_utr_under_review_on_another_order_rejected(make_user, make_order, member, order, receiving_account):
    other = make_user()
    _submit(other, make_order(other), utr=UTR)

    with pytest.raises(UtrAlreadySubmitted):
        _submit(member, order, utr=UTR)
    _assert_nothing_written_for(order)


def test_utr_of_confirmed_payin_rejected(make_user, make_order, member, order, receiving_account):
    other = make_user()
    payin = _submit(other, make_order(other), utr=UTR)
    payin.status = PayIn.Status.CONFIRMED
    payin.reviewed_at = timezone.now()
    payin.save()

    with pytest.raises(UtrAlreadySubmitted):
        _submit(member, order, utr=UTR)
    _assert_nothing_written_for(order)


def test_rejected_utr_may_be_submitted_again(member, order, receiving_account):
    # The model keeps a UTR unique only while UNDER_REVIEW/CONFIRMED.
    rejected = _submit(member, order, utr=UTR)
    _admin_reject(rejected)

    again = _submit(member, order, utr=UTR)
    assert again.pk != rejected.pk
    assert again.status == PayIn.Status.UNDER_REVIEW


# --- N. idempotency -----------------------------------------------------------

def test_same_key_returns_existing_payin(member, order, receiving_account):
    first = _submit(member, order, key='same')
    order_before = Order.objects.values().get(pk=order.pk)

    again = _submit(member, order, key='same')

    assert again.pk == first.pk
    assert PayIn.objects.count() == 1
    assert _submitted_ledger().count() == 1
    assert Order.objects.values().get(pk=order.pk) == order_before


def test_same_key_returns_existing_even_after_rejection(member, order, receiving_account):
    first = _submit(member, order, key='same')
    _admin_reject(first)

    again = _submit(member, order, key='same')
    assert again.pk == first.pk
    assert again.status == PayIn.Status.REJECTED
    assert PayIn.objects.count() == 1
    assert Order.objects.get(pk=order.pk).status == Order.Status.PAYMENT_REQUIRED


def test_same_key_with_matching_payer_app_replays(member, order, receiving_account, provider):
    first = _submit(member, order, key='same', payer_app=provider.pk)
    assert _submit(member, order, key='same').pk == first.pk
    assert _submit(member, order, key='same', payer_app=provider.pk).pk == first.pk


def test_same_key_for_different_order_conflicts(member, order, make_order, receiving_account):
    _submit(member, order, key='same')
    other_order = make_order(member)

    with pytest.raises(IdempotencyKeyConflict):
        _submit(member, other_order, key='same')
    assert not other_order.pay_ins.exists()


def test_same_key_with_different_utr_conflicts(member, order, receiving_account):
    _submit(member, order, utr='111111111111', key='same')
    with pytest.raises(IdempotencyKeyConflict):
        _submit(member, order, utr='222222222222', key='same')
    assert PayIn.objects.count() == 1


def test_same_key_with_different_payer_app_conflicts(member, order, receiving_account, provider):
    other_app = PaymentProvider.objects.create(
        code='GPAY', display_name='Google Pay', enabled_for_pay_in=True
    )
    _submit(member, order, key='same', payer_app=provider.pk)
    with pytest.raises(IdempotencyKeyConflict):
        _submit(member, order, key='same', payer_app=other_app.pk)


def test_idempotency_keys_are_per_user(make_user, make_order, member, order, receiving_account):
    other = make_user()
    _submit(other, make_order(other), utr='111111111111', key='same')

    payin = _submit(member, order, utr='222222222222', key='same')
    assert payin.user == member
    assert PayIn.objects.count() == 2


# --- O. payer app and receiving account ---------------------------------------

@pytest.mark.parametrize('values', [
    {'enabled_for_pay_in': False},
    {'enabled_for_pay_in': True, 'is_active': False},
])
def test_unusable_payer_app_rejected(member, order, receiving_account, values):
    app = PaymentProvider.objects.create(code='GPAY', display_name='Google Pay', **values)
    with pytest.raises(PayerAppUnavailable):
        _submit(member, order, payer_app=app.pk)
    _assert_nothing_written(order)


@pytest.mark.parametrize('payer_app', [999999, 'abc'])
def test_unknown_payer_app_rejected(member, order, receiving_account, payer_app):
    with pytest.raises(PayerAppUnavailable):
        _submit(member, order, payer_app=payer_app)
    _assert_nothing_written(order)


def test_payee_snapshot_uses_intent_account(member, order, provider):
    _account(provider, payee_vpa='later@ybl', priority=2)
    first = _account(provider, payee_vpa='first@ybl', priority=1)
    intent = _intent(member, order)

    payin = _submit(member, order, intent_token=intent.intent_token)
    assert payin.receiving_account == first
    assert payin.payee_vpa_snapshot == 'first@ybl' == intent.payee_vpa


# --- O2. the intent's receiving account is authoritative ----------------------

def test_intent_account_kept_when_preferred_account_changes(member, order, provider):
    shown = _account(provider, payee_vpa='shown@ybl', priority=5)
    intent = _intent(member, order)
    # After the member saw the intent, a new account takes precedence.
    _account(provider, payee_vpa='newer@ybl', priority=0)

    payin = _submit(member, order, intent_token=intent.intent_token)
    assert payin.receiving_account == shown
    assert payin.payee_vpa_snapshot == 'shown@ybl'


def test_intent_account_kept_after_deactivation(member, order, provider):
    shown = _account(provider, payee_vpa='shown@ybl')
    intent = _intent(member, order)
    shown.is_active = False
    shown.save()

    payin = _submit(member, order, intent_token=intent.intent_token)
    assert payin.receiving_account == shown
    assert payin.payee_vpa_snapshot == 'shown@ybl'


def test_snapshot_is_vpa_at_submission(member, order, provider):
    shown = _account(provider, payee_vpa='shown@ybl')
    intent = _intent(member, order)
    shown.payee_vpa = 'edited@ybl'
    shown.save()

    payin = _submit(member, order, intent_token=intent.intent_token)
    assert payin.receiving_account == shown
    assert payin.payee_vpa_snapshot == 'edited@ybl'


def test_intent_token_is_stable_across_calls(member, order, receiving_account):
    assert _intent(member, order).intent_token == _intent(member, order).intent_token


@pytest.mark.parametrize('token', ['', None, 'not-a-token', 123])
def test_missing_or_garbage_intent_token_rejected(member, order, receiving_account, token):
    with pytest.raises(InvalidPayInIntent):
        submit_pay_in_utr(
            user=member, order_id=order.pk, utr=UTR, idempotency_key=next_key(),
            intent_token=token,
        )
    _assert_nothing_written(order)


def test_tampered_intent_token_rejected(member, order, provider):
    _account(provider, payee_vpa='shown@ybl', priority=0)
    other = _account(provider, payee_vpa='other@ybl', priority=1)
    token = _intent(member, order).intent_token
    payload, signature = token.rsplit(':', 1)
    forged_payload = signing.b64_encode(
        ('{"u":%d,"o":%d,"a":%d}' % (member.pk, order.pk, other.pk)).encode()
    ).decode()
    forged = f'{forged_payload}:{signature}'
    assert forged != token

    with pytest.raises(InvalidPayInIntent):
        _submit(member, order, intent_token=forged)
    _assert_nothing_written(order)


def test_token_signed_for_another_purpose_rejected(member, order, provider):
    _account(provider, payee_vpa='shown@ybl')
    other = _account(provider, payee_vpa='other@ybl', priority=9)
    forged = signing.dumps(
        {'u': member.pk, 'o': order.pk, 'a': other.pk}, salt='some-other-salt'
    )
    with pytest.raises(InvalidPayInIntent):
        _submit(member, order, intent_token=forged)
    _assert_nothing_written(order)


def test_intent_token_for_another_order_rejected(member, order, make_order, receiving_account):
    other_order = make_order(member)
    token = _intent(member, other_order).intent_token

    with pytest.raises(InvalidPayInIntent):
        _submit(member, order, intent_token=token)
    _assert_nothing_written_for(order)
    _assert_nothing_written_for(other_order)


def test_intent_token_for_another_user_rejected(make_user, make_order, member, order, receiving_account):
    other = make_user()
    other_order = make_order(other)
    token = _intent(other, other_order).intent_token

    with pytest.raises(InvalidPayInIntent):
        submit_pay_in_utr(
            user=member, order_id=other_order.pk, utr=UTR, idempotency_key=next_key(),
            intent_token=token,
        )
    assert not PayIn.objects.exists()


def test_replay_with_token_for_different_account_conflicts(member, order, provider):
    first = _account(provider, payee_vpa='first@ybl', priority=1)
    second = _account(provider, payee_vpa='second@ybl', priority=2)
    _submit(member, order, key='same', intent_token=_token_for(member, order, first))

    with pytest.raises(IdempotencyKeyConflict):
        _submit(member, order, key='same', intent_token=_token_for(member, order, second))
    assert PayIn.objects.count() == 1


def test_intent_then_submit_end_to_end(member, order, receiving_account):
    intent = _intent(member, order)
    payin = submit_pay_in_utr(
        user=member, order_id=order.pk, utr=UTR, idempotency_key=next_key(),
        intent_token=intent.intent_token,
    )
    assert payin.receiving_account_id == intent.receiving_account_id
    assert payin.amount == intent.amount


# --- P. concurrency (PostgreSQL only) -----------------------------------------

postgres_only = pytest.mark.skipif(
    connection.vendor != 'postgresql',
    reason='Row locks (select_for_update) are a no-op on SQLite.',
)


def _run_concurrently(*calls):
    barrier = threading.Barrier(len(calls))
    results = [None] * len(calls)

    def worker(index, call):
        try:
            barrier.wait()
            results[index] = call()
        except Exception as exc:
            results[index] = exc
        finally:
            connection.close()

    threads = [threading.Thread(target=worker, args=(i, c)) for i, c in enumerate(calls)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    return results


@postgres_only
@pytest.mark.django_db(transaction=True)
def test_concurrent_retries_with_same_key_create_one_payin(member, order, receiving_account):
    results = _run_concurrently(
        *[lambda: _submit(member, order, key='same') for _ in range(3)]
    )

    assert all(isinstance(r, PayIn) for r in results), results
    assert len({r.pk for r in results}) == 1
    assert _submitted_ledger().count() == 1


@postgres_only
@pytest.mark.django_db(transaction=True)
def test_concurrent_different_submissions_for_one_order(member, order, receiving_account):
    results = _run_concurrently(
        lambda: _submit(member, order, utr='111111111111'),
        lambda: _submit(member, order, utr='222222222222'),
    )

    payins = [r for r in results if isinstance(r, PayIn)]
    errors = [r for r in results if not isinstance(r, PayIn)]
    assert len(payins) == 1
    assert len(errors) == 1 and isinstance(errors[0], OrderNotPayable)
    assert PayIn.objects.count() == 1
    assert _submitted_ledger().count() == 1


@postgres_only
@pytest.mark.django_db(transaction=True)
def test_concurrent_same_utr_on_two_orders(make_user, make_order, receiving_account):
    first, second = make_user(), make_user()
    first_order, second_order = make_order(first), make_order(second)

    results = _run_concurrently(
        lambda: _submit(first, first_order, utr=UTR),
        lambda: _submit(second, second_order, utr=UTR),
    )

    payins = [r for r in results if isinstance(r, PayIn)]
    errors = [r for r in results if not isinstance(r, PayIn)]
    assert len(payins) == 1
    assert len(errors) == 1 and isinstance(errors[0], UtrAlreadySubmitted)
    assert PayIn.objects.filter(utr=UTR).count() == 1


# =============================================================================
# confirm_pay_in / reject_pay_in
# =============================================================================

REASON = 'UTR not found in bank statement'
SNAPSHOT_FIELDS = (
    'order_amount', 'income_rate', 'income_amount', 'payout_target', 'payout_method_id',
    'payout_committed_total', 'payout_paid_total', 'currency', 'reference', 'completed_at',
)


@pytest.fixture
def super_admin(make_user):
    return make_user(role=User.Role.SUPER_ADMIN)


@pytest.fixture
def pending(member, order, receiving_account):
    """An UNDER_REVIEW PayIn submitted by the member."""
    return _submit(member, order, utr='555555555555')


def _confirm(admin, payin, key=None):
    return confirm_pay_in(admin=admin, payin_id=payin.pk, idempotency_key=key or next_key())


def _reject_via_service(admin, payin, reason=REASON, key=None):
    return reject_pay_in(
        admin=admin, payin_id=payin.pk, rejection_reason=reason,
        idempotency_key=key or next_key(),
    )


def _snapshot(order):
    values = Order.objects.values().get(pk=order.pk)
    return {field: values[field] for field in SNAPSHOT_FIELDS}


def _review_ledger(action):
    return LedgerEntry.objects.filter(action=action)


def _assert_review_unchanged(payin):
    payin.refresh_from_db()
    assert payin.status == PayIn.Status.UNDER_REVIEW
    assert payin.reviewed_by is None and payin.reviewed_at is None
    assert Order.objects.get(pk=payin.order_id).status == Order.Status.PAYMENT_UNDER_REVIEW
    assert not _review_ledger(LedgerEntry.Action.PAYIN_CONFIRMED).exists()
    assert not _review_ledger(LedgerEntry.Action.PAYIN_REJECTED).exists()


# --- Q. confirm: success ------------------------------------------------------

@pytest.mark.parametrize('reviewer', ['admin_user', 'super_admin'])
def test_admin_can_confirm(request, pending, reviewer):
    admin = request.getfixturevalue(reviewer)
    payin = _confirm(admin, pending, key='confirm-1')

    payin.refresh_from_db()
    assert payin.status == PayIn.Status.CONFIRMED
    assert payin.reviewed_by == admin
    assert payin.reviewed_at is not None
    assert payin.review_idempotency_key == 'confirm-1'
    assert payin.rejection_reason == ''


def test_confirm_moves_order_to_active(admin_user, pending, order):
    _confirm(admin_user, pending)

    order.refresh_from_db()
    assert order.status == Order.Status.ACTIVE
    assert order.activated_at is not None
    assert order.completed_at is None


def test_confirm_amount_is_order_amount(admin_user, pending, order):
    payin = _confirm(admin_user, pending)
    entry = _review_ledger(LedgerEntry.Action.PAYIN_CONFIRMED).get()

    assert payin.amount == entry.amount == order.order_amount == Decimal('255.00')
    assert entry.amount not in (order.income_amount, order.payout_target)


def test_confirm_writes_payin_confirmed_ledger_entry(admin_user, pending, order):
    payin = _confirm(admin_user, pending)

    entry = _review_ledger(LedgerEntry.Action.PAYIN_CONFIRMED).get()
    assert entry.actor == admin_user
    assert entry.order == order
    assert entry.payin == payin
    assert entry.balance_before == Decimal('0')
    assert entry.balance_after == order.payout_target == Decimal('265')
    assert entry.reference_id == order.reference
    assert not _review_ledger(LedgerEntry.Action.PAYIN_REJECTED).exists()


def test_confirm_does_not_pay_out_or_complete(admin_user, pending, order):
    before = _snapshot(order)
    _confirm(admin_user, pending)

    assert not Payout.objects.exists()
    assert _snapshot(order) == before
    assert Order.objects.get(pk=order.pk).status != Order.Status.COMPLETED
    payout_actions = {a for a in LedgerEntry.Action.values if a.startswith('PAYOUT_')}
    assert not LedgerEntry.objects.filter(action__in=payout_actions).exists()
    assert not LedgerEntry.objects.filter(action=LedgerEntry.Action.ORDER_COMPLETED).exists()


def test_confirm_leaves_earlier_rejected_payin_unchanged(admin_user, member, order, receiving_account):
    first = _submit(member, order, utr='111111111111')
    _reject_via_service(admin_user, first)
    rejected = PayIn.objects.values().get(pk=first.pk)

    second = _submit(member, order, utr='222222222222')
    _confirm(admin_user, second)

    assert PayIn.objects.values().get(pk=first.pk) == rejected


# --- R. confirm: refusals -----------------------------------------------------

@pytest.mark.parametrize('extra', [
    {},                                                 # member
    {'role': User.Role.ADMIN, 'is_active': False},
    {'role': User.Role.SUPER_ADMIN, 'is_active': False},
])
def test_non_admin_or_inactive_admin_cannot_confirm(make_user, pending, extra):
    with pytest.raises(ReviewPermissionDenied):
        _confirm(make_user(**extra), pending)
    _assert_review_unchanged(pending)


def test_anonymous_cannot_confirm(pending):
    with pytest.raises(ReviewPermissionDenied):
        _confirm(AnonymousUser(), pending)
    _assert_review_unchanged(pending)


def test_owner_member_cannot_confirm_own_payin(member, pending):
    with pytest.raises(ReviewPermissionDenied):
        _confirm(member, pending)
    _assert_review_unchanged(pending)


@pytest.mark.parametrize('payin_id', [999999, 'abc', None])
def test_confirm_unknown_payin(admin_user, pending, payin_id):
    with pytest.raises(PayInNotFound):
        confirm_pay_in(admin=admin_user, payin_id=payin_id, idempotency_key=next_key())
    _assert_review_unchanged(pending)


def test_confirmed_payin_cannot_be_confirmed_again(admin_user, super_admin, pending):
    _confirm(admin_user, pending)
    with pytest.raises(PayInAlreadyReviewed):
        _confirm(super_admin, pending)
    assert _review_ledger(LedgerEntry.Action.PAYIN_CONFIRMED).count() == 1


def test_rejected_payin_cannot_be_confirmed(admin_user, pending):
    _reject_via_service(admin_user, pending)
    with pytest.raises(PayInAlreadyReviewed):
        _confirm(admin_user, pending)
    pending.refresh_from_db()
    assert pending.status == PayIn.Status.REJECTED
    assert Order.objects.get(pk=pending.order_id).status == Order.Status.PAYMENT_REQUIRED
    assert not _review_ledger(LedgerEntry.Action.PAYIN_CONFIRMED).exists()


def test_confirm_refused_when_order_state_disagrees(admin_user, pending):
    Order.objects.filter(pk=pending.order_id).update(status=Order.Status.PAYMENT_REQUIRED)
    with pytest.raises(PayInConflict):
        _confirm(admin_user, pending)
    pending.refresh_from_db()
    assert pending.status == PayIn.Status.UNDER_REVIEW


@pytest.mark.parametrize('key', ['', '   ', None, 'x' * 65])
def test_confirm_invalid_idempotency_key(admin_user, pending, key):
    with pytest.raises(InvalidIdempotencyKey):
        confirm_pay_in(admin=admin_user, payin_id=pending.pk, idempotency_key=key)
    _assert_review_unchanged(pending)


# --- S. confirm: idempotency --------------------------------------------------

def test_confirm_replay_returns_same_payin(admin_user, pending, order):
    first = _confirm(admin_user, pending, key='same')
    order_before = Order.objects.values().get(pk=order.pk)

    again = _confirm(admin_user, pending, key='same')

    assert again.pk == first.pk
    assert again.status == PayIn.Status.CONFIRMED
    assert _review_ledger(LedgerEntry.Action.PAYIN_CONFIRMED).count() == 1
    assert Order.objects.values().get(pk=order.pk) == order_before


def test_confirm_key_reused_for_other_payin_conflicts(
    admin_user, pending, make_user, make_order, receiving_account
):
    _confirm(admin_user, pending, key='same')
    other = make_user()
    other_payin = _submit(other, make_order(other), utr='666666666666')

    with pytest.raises(IdempotencyKeyConflict):
        _confirm(admin_user, other_payin, key='same')
    other_payin.refresh_from_db()
    assert other_payin.status == PayIn.Status.UNDER_REVIEW


def test_confirm_key_reused_for_reject_conflicts(admin_user, pending):
    _confirm(admin_user, pending, key='same')
    with pytest.raises(IdempotencyKeyConflict):
        _reject_via_service(admin_user, pending, key='same')
    pending.refresh_from_db()
    assert pending.status == PayIn.Status.CONFIRMED


def test_review_keys_are_per_admin(admin_user, super_admin, pending, make_user, make_order):
    _confirm(admin_user, pending, key='same')
    other = make_user()
    other_payin = _submit(other, make_order(other), utr='666666666666')

    assert _confirm(super_admin, other_payin, key='same').status == PayIn.Status.CONFIRMED


# --- T. reject: success -------------------------------------------------------

@pytest.mark.parametrize('reviewer', ['admin_user', 'super_admin'])
def test_admin_can_reject(request, pending, reviewer):
    admin = request.getfixturevalue(reviewer)
    payin = _reject_via_service(admin, pending, key='reject-1')

    payin.refresh_from_db()
    assert payin.status == PayIn.Status.REJECTED
    assert payin.rejection_reason == REASON
    assert payin.reviewed_by == admin
    assert payin.reviewed_at is not None
    assert payin.review_idempotency_key == 'reject-1'


def test_reject_returns_order_to_payment_required(admin_user, pending, order):
    _reject_via_service(admin_user, pending)
    order.refresh_from_db()
    assert order.status == Order.Status.PAYMENT_REQUIRED
    assert order.activated_at is None


def test_reject_keeps_payin_and_utr(admin_user, pending):
    _reject_via_service(admin_user, pending)

    stored = PayIn.objects.get(pk=pending.pk)
    assert stored.utr == '555555555555'
    assert stored.amount == pending.amount
    assert stored.order_id == pending.order_id


def test_reject_does_not_pay_out_or_touch_snapshot(admin_user, pending, order):
    before = _snapshot(order)
    _reject_via_service(admin_user, pending)

    assert not Payout.objects.exists()
    assert _snapshot(order) == before


def test_reject_writes_no_confirmed_ledger_entry(admin_user, pending, order):
    payin = _reject_via_service(admin_user, pending)

    assert not _review_ledger(LedgerEntry.Action.PAYIN_CONFIRMED).exists()
    entry = _review_ledger(LedgerEntry.Action.PAYIN_REJECTED).get()
    assert entry.actor == admin_user
    assert entry.order == order
    assert entry.payin == payin
    assert entry.amount == order.order_amount
    assert entry.balance_before is None and entry.balance_after is None
    assert entry.reference_id == order.reference


def test_after_rejection_new_utr_creates_new_payin(admin_user, member, pending, order):
    _reject_via_service(admin_user, pending)

    fresh = _submit(member, order, utr='777777777777')

    assert fresh.pk != pending.pk
    assert fresh.status == PayIn.Status.UNDER_REVIEW
    assert order.pay_ins.count() == 2
    assert PayIn.objects.get(pk=pending.pk).status == PayIn.Status.REJECTED
    assert Order.objects.get(pk=order.pk).status == Order.Status.PAYMENT_UNDER_REVIEW


# --- U. reject: refusals ------------------------------------------------------

@pytest.mark.parametrize('extra', [
    {},                                                 # member
    {'role': User.Role.ADMIN, 'is_active': False},
    {'role': User.Role.SUPER_ADMIN, 'is_active': False},
])
def test_non_admin_or_inactive_admin_cannot_reject(make_user, pending, extra):
    with pytest.raises(ReviewPermissionDenied):
        _reject_via_service(make_user(**extra), pending)
    _assert_review_unchanged(pending)


@pytest.mark.parametrize('payin_id', [999999, 'abc', None])
def test_reject_unknown_payin(admin_user, pending, payin_id):
    with pytest.raises(PayInNotFound):
        reject_pay_in(
            admin=admin_user, payin_id=payin_id, rejection_reason=REASON,
            idempotency_key=next_key(),
        )
    _assert_review_unchanged(pending)


def test_confirmed_payin_cannot_be_rejected(admin_user, pending):
    _confirm(admin_user, pending)
    with pytest.raises(PayInAlreadyReviewed):
        _reject_via_service(admin_user, pending)
    pending.refresh_from_db()
    assert pending.status == PayIn.Status.CONFIRMED
    assert Order.objects.get(pk=pending.order_id).status == Order.Status.ACTIVE
    assert not _review_ledger(LedgerEntry.Action.PAYIN_REJECTED).exists()


def test_rejected_payin_cannot_be_rejected_again(admin_user, super_admin, pending):
    _reject_via_service(admin_user, pending)
    with pytest.raises(PayInAlreadyReviewed):
        _reject_via_service(super_admin, pending, reason='another reason')
    pending.refresh_from_db()
    assert pending.rejection_reason == REASON
    assert _review_ledger(LedgerEntry.Action.PAYIN_REJECTED).count() == 1


@pytest.mark.parametrize('reason', ['', '   ', '\n\t', None, 42, 'x' * 501])
def test_reject_requires_valid_reason(admin_user, pending, reason):
    with pytest.raises(InvalidRejectionReason):
        _reject_via_service(admin_user, pending, reason=reason)
    _assert_review_unchanged(pending)


def test_reject_reason_of_500_chars_accepted_as_given(admin_user, pending):
    reason = ' ' + 'x' * 499
    payin = _reject_via_service(admin_user, pending, reason=reason)
    payin.refresh_from_db()
    assert payin.rejection_reason == reason


# --- V. reject: idempotency ---------------------------------------------------

def test_reject_replay_returns_same_payin(admin_user, pending, order):
    first = _reject_via_service(admin_user, pending, key='same')
    order_before = Order.objects.values().get(pk=order.pk)

    again = _reject_via_service(admin_user, pending, key='same')

    assert again.pk == first.pk
    assert _review_ledger(LedgerEntry.Action.PAYIN_REJECTED).count() == 1
    assert Order.objects.values().get(pk=order.pk) == order_before


def test_reject_replay_survives_resubmission(admin_user, member, pending, order):
    _reject_via_service(admin_user, pending, key='same')
    _submit(member, order, utr='777777777777')

    again = _reject_via_service(admin_user, pending, key='same')
    assert again.pk == pending.pk
    assert Order.objects.get(pk=order.pk).status == Order.Status.PAYMENT_UNDER_REVIEW
    assert _review_ledger(LedgerEntry.Action.PAYIN_REJECTED).count() == 1


def test_reject_key_with_different_reason_conflicts(admin_user, pending):
    _reject_via_service(admin_user, pending, key='same')
    with pytest.raises(IdempotencyKeyConflict):
        _reject_via_service(admin_user, pending, reason='different', key='same')


def test_reject_key_reused_for_confirm_conflicts(admin_user, pending):
    _reject_via_service(admin_user, pending, key='same')
    with pytest.raises(IdempotencyKeyConflict):
        _confirm(admin_user, pending, key='same')
    pending.refresh_from_db()
    assert pending.status == PayIn.Status.REJECTED


def test_reject_key_reused_for_other_payin_conflicts(
    admin_user, pending, make_user, make_order
):
    _reject_via_service(admin_user, pending, key='same')
    other = make_user()
    other_payin = _submit(other, make_order(other), utr='666666666666')

    with pytest.raises(IdempotencyKeyConflict):
        _reject_via_service(admin_user, other_payin, key='same')
    other_payin.refresh_from_db()
    assert other_payin.status == PayIn.Status.UNDER_REVIEW


# --- W. review concurrency (PostgreSQL only) ----------------------------------

@postgres_only
@pytest.mark.django_db(transaction=True)
def test_concurrent_confirm_and_reject_one_wins(admin_user, super_admin, pending):
    results = _run_concurrently(
        lambda: _confirm(admin_user, pending),
        lambda: _reject_via_service(super_admin, pending),
    )

    winners = [r for r in results if isinstance(r, PayIn)]
    losers = [r for r in results if not isinstance(r, PayIn)]
    assert len(winners) == 1
    assert len(losers) == 1 and isinstance(losers[0], PayInAlreadyReviewed)

    pending.refresh_from_db()
    order = Order.objects.get(pk=pending.order_id)
    decided = LedgerEntry.objects.filter(
        action__in=[LedgerEntry.Action.PAYIN_CONFIRMED, LedgerEntry.Action.PAYIN_REJECTED]
    )
    assert decided.count() == 1
    if pending.status == PayIn.Status.CONFIRMED:
        assert order.status == Order.Status.ACTIVE
    else:
        assert order.status == Order.Status.PAYMENT_REQUIRED


@postgres_only
@pytest.mark.django_db(transaction=True)
def test_concurrent_confirm_retries_with_same_key(admin_user, pending):
    results = _run_concurrently(
        *[lambda: _confirm(admin_user, pending, key='same') for _ in range(3)]
    )

    assert all(isinstance(r, PayIn) for r in results), results
    assert _review_ledger(LedgerEntry.Action.PAYIN_CONFIRMED).count() == 1


@postgres_only
@pytest.mark.django_db(transaction=True)
def test_concurrent_confirms_by_two_admins(admin_user, super_admin, pending):
    results = _run_concurrently(
        lambda: _confirm(admin_user, pending),
        lambda: _confirm(super_admin, pending),
    )

    assert sum(isinstance(r, PayIn) for r in results) == 1
    assert sum(isinstance(r, PayInAlreadyReviewed) for r in results) == 1
    assert _review_ledger(LedgerEntry.Action.PAYIN_CONFIRMED).count() == 1
