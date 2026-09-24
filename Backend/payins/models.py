from django.conf import settings
from django.core.exceptions import ValidationError
from django.db import models
from django.db.models import Q
from django.utils import timezone

from audit.mixins import ImmutableFieldsMixin, UndeletableModel, whole_rupees
from paymethods.validators import UTR_LENGTH, UTR_PATTERN, utr_validator


class PayIn(ImmutableFieldsMixin, UndeletableModel):
    """
    A member's claim that they paid the platform for an order, backed by a
    UTR. It proves nothing until an admin reviews it. A rejected PayIn stays
    on record, frozen; a resubmission is a new row.

    user and amount (the order's order_amount) always come from the order,
    never from the client.
    """

    class Status(models.TextChoices):
        UNDER_REVIEW = 'UNDER_REVIEW', 'Under review'
        CONFIRMED = 'CONFIRMED', 'Confirmed'
        REJECTED = 'REJECTED', 'Rejected'

    final_statuses = (Status.CONFIRMED, Status.REJECTED)

    immutable_fields = (
        'order',
        'user',
        'amount',
        'receiving_account',
        'payee_vpa_snapshot',
        'utr',
        'idempotency_key',
        'submitted_at',
    )

    order = models.ForeignKey('orders.Order', on_delete=models.PROTECT, related_name='pay_ins')
    user = models.ForeignKey(
        settings.AUTH_USER_MODEL,
        on_delete=models.PROTECT,
        related_name='pay_ins',
    )
    amount = models.DecimalField(max_digits=12, decimal_places=2)
    receiving_account = models.ForeignKey(
        'paymethods.PlatformReceivingAccount',
        on_delete=models.PROTECT,
        related_name='pay_ins',
    )
    payee_vpa_snapshot = models.CharField(
        max_length=255,
        help_text='Receiving VPA at submission time, preserved if the account changes.',
    )
    payer_app = models.ForeignKey(
        'paymethods.PaymentProvider',
        on_delete=models.PROTECT,
        null=True,
        blank=True,
        related_name='+',
        help_text='UPI app the member says they paid with (informational).',
    )
    utr = models.CharField(max_length=UTR_LENGTH, validators=[utr_validator])
    status = models.CharField(
        max_length=16,
        choices=Status.choices,
        default=Status.UNDER_REVIEW,
    )
    rejection_reason = models.TextField(blank=True)
    reviewed_by = models.ForeignKey(
        settings.AUTH_USER_MODEL,
        on_delete=models.PROTECT,
        null=True,
        blank=True,
        related_name='+',
    )
    reviewed_at = models.DateTimeField(null=True, blank=True)
    review_idempotency_key = models.CharField(
        max_length=64,
        null=True,
        blank=True,
        help_text="The reviewing admin's idempotency key for the confirm/reject.",
    )
    submitted_at = models.DateTimeField(default=timezone.now)
    idempotency_key = models.CharField(max_length=64)

    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        constraints = [
            models.CheckConstraint(
                condition=Q(amount__gt=0),
                name='payins_payin_amount_positive',
            ),
            whole_rupees('amount', 'payins_payin_amount_whole_rupees'),
            models.CheckConstraint(
                condition=Q(utr__regex=UTR_PATTERN),
                name='payins_payin_utr_12_digits',
            ),
            models.CheckConstraint(
                condition=~Q(payee_vpa_snapshot=''),
                name='payins_payin_payee_snapshot_required',
            ),
            models.CheckConstraint(
                condition=Q(status='UNDER_REVIEW') | Q(reviewed_at__isnull=False),
                name='payins_payin_reviewed_at_when_decided',
            ),
            # Partial unique indexes (supported by PostgreSQL and SQLite).
            models.UniqueConstraint(
                fields=['order'],
                condition=Q(status='UNDER_REVIEW'),
                name='payins_payin_one_under_review_per_order',
            ),
            models.UniqueConstraint(
                fields=['order'],
                condition=Q(status='CONFIRMED'),
                name='payins_payin_one_confirmed_per_order',
            ),
            models.UniqueConstraint(
                fields=['utr'],
                condition=Q(status__in=['UNDER_REVIEW', 'CONFIRMED']),
                name='payins_payin_utr_unique_while_active',
            ),
            models.UniqueConstraint(
                fields=['user', 'idempotency_key'],
                name='payins_payin_unique_idempotency_key_per_user',
            ),
            models.UniqueConstraint(
                fields=['reviewed_by', 'review_idempotency_key'],
                condition=Q(review_idempotency_key__isnull=False),
                name='payins_payin_unique_review_key_per_reviewer',
            ),
        ]
        indexes = [
            models.Index(fields=['status', 'submitted_at']),
        ]

    def save(self, *args, **kwargs):
        if self._state.adding:
            order = self.order
            if self.user_id is None:
                self.user_id = order.user_id
            if self.amount is None:
                self.amount = order.order_amount
            if self.user_id != order.user_id:
                raise ValidationError('PayIn user must be the order owner.')
            if self.amount != order.order_amount:
                raise ValidationError('PayIn amount must equal the order amount.')
        super().save(*args, **kwargs)

    def __str__(self):
        return f'PayIn {self.pk} for {self.order_id}'
