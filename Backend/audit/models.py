from django.conf import settings
from django.db import models
from django.db.models import Q

from audit.mixins import AppendOnlyModel


class LedgerEntry(AppendOnlyModel):
    """
    Append-only record of every financial state change on an order.
    Corrections are new offsetting entries, never edits.

    balance_before / balance_after track the order's outstanding payout
    (payout_target - payout_paid_total). This is not a user wallet balance.
    """

    class Action(models.TextChoices):
        ORDER_CLAIMED = 'ORDER_CLAIMED', 'Order claimed'
        ORDER_EXPIRED = 'ORDER_EXPIRED', 'Order expired'
        ORDER_CANCELLED = 'ORDER_CANCELLED', 'Order cancelled'
        ORDER_COMPLETED = 'ORDER_COMPLETED', 'Order completed'
        PAYIN_SUBMITTED = 'PAYIN_SUBMITTED', 'Pay-in submitted'
        PAYIN_CONFIRMED = 'PAYIN_CONFIRMED', 'Pay-in confirmed'
        PAYIN_REJECTED = 'PAYIN_REJECTED', 'Pay-in rejected'
        PAYOUT_CREATED = 'PAYOUT_CREATED', 'Payout created'
        PAYOUT_PAID = 'PAYOUT_PAID', 'Payout paid'
        PAYOUT_FAILED = 'PAYOUT_FAILED', 'Payout failed'
        PAYOUT_CANCELLED = 'PAYOUT_CANCELLED', 'Payout cancelled'
        # A team commission created by the order's confirmed pay-in. It does
        # not change the order's outstanding payout, so balances are empty.
        COMMISSION_EARNED = 'COMMISSION_EARNED', 'Commission earned'

    actor = models.ForeignKey(
        settings.AUTH_USER_MODEL,
        on_delete=models.PROTECT,
        null=True,
        blank=True,
        related_name='+',
        help_text='Empty for system actions (e.g. expiry).',
    )
    action = models.CharField(max_length=32, choices=Action.choices)
    order = models.ForeignKey('orders.Order', on_delete=models.PROTECT, related_name='ledger_entries')
    payin = models.ForeignKey(
        'payins.PayIn',
        on_delete=models.PROTECT,
        null=True,
        blank=True,
        related_name='ledger_entries',
    )
    payout = models.ForeignKey(
        'payouts.Payout',
        on_delete=models.PROTECT,
        null=True,
        blank=True,
        related_name='ledger_entries',
    )
    commission = models.ForeignKey(
        'team.Commission',
        on_delete=models.PROTECT,
        null=True,
        blank=True,
        related_name='ledger_entries',
    )
    amount = models.DecimalField(max_digits=12, decimal_places=2)
    balance_before = models.DecimalField(max_digits=12, decimal_places=2, null=True, blank=True)
    balance_after = models.DecimalField(max_digits=12, decimal_places=2, null=True, blank=True)
    reference_id = models.CharField(max_length=64, blank=True)
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        ordering = ['created_at', 'id']
        verbose_name_plural = 'ledger entries'
        constraints = [
            models.CheckConstraint(
                condition=Q(amount__gte=0),
                name='audit_ledger_amount_non_negative',
            ),
            models.CheckConstraint(
                condition=Q(balance_before__isnull=True) | Q(balance_before__gte=0),
                name='audit_ledger_balance_before_non_negative',
            ),
            models.CheckConstraint(
                condition=Q(balance_after__isnull=True) | Q(balance_after__gte=0),
                name='audit_ledger_balance_after_non_negative',
            ),
        ]
        indexes = [
            models.Index(fields=['order', 'created_at']),
            models.Index(fields=['action', 'created_at']),
        ]

    def __str__(self):
        return f'{self.action} {self.amount} ({self.order_id})'


class AuditEvent(AppendOnlyModel):
    """
    Append-only security/configuration event (e.g. an admin viewed a user's
    UPI ID or QR). metadata must never hold UPI IDs, QR content, tokens or
    other secrets - only identifiers needed to investigate.
    """

    class Action(models.TextChoices):
        PAYOUT_METHOD_VIEWED = 'PAYOUT_METHOD_VIEWED', 'Payout method viewed'
        PAYOUT_METHOD_QR_VIEWED = 'PAYOUT_METHOD_QR_VIEWED', 'Payout method QR viewed'
        PAYOUT_METHOD_CREATED = 'PAYOUT_METHOD_CREATED', 'Payout method created'
        PAYOUT_METHOD_DEACTIVATED = 'PAYOUT_METHOD_DEACTIVATED', 'Payout method deactivated'
        PAYOUT_METHOD_DEFAULT_CHANGED = 'PAYOUT_METHOD_DEFAULT_CHANGED', 'Default payout method changed'
        BUY_METHOD_VIEWED = 'BUY_METHOD_VIEWED', 'Buy method viewed'
        BUY_METHOD_CREATED = 'BUY_METHOD_CREATED', 'Buy method created'
        BUY_METHOD_DEACTIVATED = 'BUY_METHOD_DEACTIVATED', 'Buy method deactivated'
        BUY_METHOD_DEFAULT_CHANGED = 'BUY_METHOD_DEFAULT_CHANGED', 'Default buy method changed'
        PROVIDER_CHANGED ='PROVIDER_CHANGED', 'Payment provider changed'
        RECEIVING_ACCOUNT_CHANGED = 'RECEIVING_ACCOUNT_CHANGED', 'Receiving account changed'
        OFFER_CHANGED = 'OFFER_CHANGED', 'Offer changed'
        ROLE_CHANGED = 'ROLE_CHANGED', 'Role changed'
        # Admin management by a SUPER_ADMIN; target is the admin account.
        ADMIN_CREATED = 'ADMIN_CREATED', 'Admin created'
        ADMIN_ACTIVATED = 'ADMIN_ACTIVATED', 'Admin activated'
        ADMIN_DEACTIVATED = 'ADMIN_DEACTIVATED', 'Admin deactivated'
        PROFILE_NAME_CHANGED = 'PROFILE_NAME_CHANGED', 'Profile name changed'
        # metadata never holds a password, hash or token.
        PASSWORD_CHANGED = 'PASSWORD_CHANGED', 'Password changed'
        # Authentication. metadata never holds a password, OTP or token.
        LOGIN_SUCCEEDED = 'LOGIN_SUCCEEDED', 'Login succeeded'
        LOGIN_FAILED = 'LOGIN_FAILED', 'Login failed'
        PASSWORD_RESET = 'PASSWORD_RESET', 'Password reset'
        # Support chat. metadata never holds message text or file names.
        # Routing is recorded once, when the conversation is created.
        SUPPORT_CONVERSATION_STARTED = 'SUPPORT_CONVERSATION_STARTED', 'Support conversation started'
        SUPPORT_CONVERSATION_CLOSED = 'SUPPORT_CONVERSATION_CLOSED', 'Support conversation closed'
        # A SUPER_ADMIN opening a conversation assigned to another admin.
        SUPPORT_CONVERSATION_VIEWED = 'SUPPORT_CONVERSATION_VIEWED', 'Support conversation viewed'
        SUPPORT_ATTACHMENT_VIEWED = 'SUPPORT_ATTACHMENT_VIEWED', 'Support attachment viewed'
        # An OPEN conversation moved off a deactivated admin by the normal routing rule.
        SUPPORT_CONVERSATION_REASSIGNED = 'SUPPORT_CONVERSATION_REASSIGNED', 'Support conversation reassigned'

    actor = models.ForeignKey(
        settings.AUTH_USER_MODEL,
        on_delete=models.PROTECT,
        null=True,
        blank=True,
        related_name='+',
    )
    action = models.CharField(max_length=64, choices=Action.choices)
    target_type = models.CharField(max_length=100, help_text='e.g. paymethods.UserPayoutMethod')
    target_id = models.CharField(max_length=64)
    ip_address = models.GenericIPAddressField(null=True, blank=True)
    metadata = models.JSONField(default=dict, blank=True)
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        ordering = ['created_at', 'id']
        indexes = [
            models.Index(fields=['target_type', 'target_id']),
            models.Index(fields=['actor', 'created_at']),
        ]

    def __str__(self):
        return f'{self.action} {self.target_type}:{self.target_id}'
