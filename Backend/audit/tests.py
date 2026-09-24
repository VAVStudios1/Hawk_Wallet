from decimal import Decimal

import pytest

from audit.mixins import AppendOnlyError
from audit.models import AuditEvent, LedgerEntry


@pytest.fixture
def entry(user, make_order):
    return LedgerEntry.objects.create(
        action=LedgerEntry.Action.ORDER_CLAIMED,
        order=make_order(user),
        amount=Decimal('255.00'),
    )


def test_ledger_entry_cannot_be_edited(entry):
    entry.amount = Decimal('1.00')
    with pytest.raises(AppendOnlyError):
        entry.save()


def test_ledger_entry_cannot_be_deleted(entry):
    with pytest.raises(AppendOnlyError):
        entry.delete()
    with pytest.raises(AppendOnlyError):
        LedgerEntry.objects.all().delete()
    with pytest.raises(AppendOnlyError):
        LedgerEntry.objects.update(amount=Decimal('0'))


def test_audit_event_is_append_only(admin_user):
    event = AuditEvent.objects.create(
        actor=admin_user,
        action=AuditEvent.Action.PAYOUT_METHOD_VIEWED,
        target_type='paymethods.UserPayoutMethod',
        target_id='1',
    )
    event.target_id = '2'
    with pytest.raises(AppendOnlyError):
        event.save()
    with pytest.raises(AppendOnlyError):
        event.delete()
