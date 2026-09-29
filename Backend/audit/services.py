from audit.models import AuditEvent, LedgerEntry


def record_ledger_entry(
    *,
    actor,
    action,
    order,
    amount,
    payin=None,
    payout=None,
    commission=None,
    balance_before=None,
    balance_after=None,
    reference_id='',
):
    """
    Append one LedgerEntry. balance_before/after are the order's outstanding
    payout (payout_target - payout_paid_total); leave them empty for events
    that don't change what the platform owes.
    """
    return LedgerEntry.objects.create(
        actor=actor,
        action=action,
        order=order,
        amount=amount,
        payin=payin,
        payout=payout,
        commission=commission,
        balance_before=balance_before,
        balance_after=balance_after,
        reference_id=reference_id,
    )


def record_audit_event(*, actor, action, target, ip_address=None, metadata=None):
    """
    Append one AuditEvent about `target`. metadata must hold identifiers
    only - never UPI IDs, QR content, tokens or other secrets.
    """
    return AuditEvent.objects.create(
        actor=actor,
        action=action,
        target_type=target._meta.label,
        target_id=str(target.pk),
        ip_address=ip_address,
        metadata=metadata or {},
    )
