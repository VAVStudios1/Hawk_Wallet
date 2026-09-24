from django.conf import settings
from django.core.exceptions import ValidationError
from django.db import models
from django.db.models import Q

from audit.mixins import ImmutableFieldsMixin, UndeletableModel, whole_rupees
from paymethods.validators import UTR_LENGTH, UTR_PATTERN, utr_validator


class Payout(ImmutableFieldsMixin, UndeletableModel):
    """
    One transfer from the platform to the user. An order can have any number
    of payouts; the order's running totals (DB-constrained) cap their sum.

    Payouts always go to the payout method fixed on the order. The
    destination is snapshotted on creation, so the record keeps showing
    where money actually went. A PAID payout requires a UTR and is frozen.
    """

    class Status(models.TextChoices):
        PENDING = 'PENDING', 'Pending'
        PAID = 'PAID', 'Paid'
        FAILED = 'FAILED', 'Failed'
        CANCELLED = 'CANCELLED', 'Cancelled'

    final_statuses = (Status.PAID,)

    immutable_fields = (
        'order',
        'user',
        'payout_method',
        'upi_id_snapshot',
        'provider_code_snapshot',
        'provider_name_snapshot',
        'amount',
        'idempotency_key',
        'created_by',
    )

    order = models.ForeignKey('orders.Order', on_delete=models.PROTECT, related_name='payouts')
    user = models.ForeignKey(
        settings.AUTH_USER_MODEL,
        on_delete=models.PROTECT,
        related_name='payouts',
    )
    payout_method = models.ForeignKey(
        'paymethods.UserPayoutMethod',
        on_delete=models.PROTECT,
        related_name='payouts',
    )
    upi_id_snapshot = models.CharField(max_length=255)
    provider_code_snapshot = models.CharField(max_length=32)
    provider_name_snapshot = models.CharField(max_length=64)

    amount = models.DecimalField(max_digits=12, decimal_places=2)
    status = models.CharField(max_length=16, choices=Status.choices, default=Status.PENDING)
    utr = models.CharField(
        max_length=UTR_LENGTH,
        blank=True,
        validators=[utr_validator],
        help_text='12-digit UTR; required before the payout can be PAID.',
    )

    created_by = models.ForeignKey(
        settings.AUTH_USER_MODEL,
        on_delete=models.PROTECT,
        related_name='+',
    )
    paid_by = models.ForeignKey(
        settings.AUTH_USER_MODEL,
        on_delete=models.PROTECT,
        null=True,
        blank=True,
        related_name='+',
    )
    created_at = models.DateTimeField(auto_now_add=True)
    paid_at = models.DateTimeField(null=True, blank=True)
    updated_at = models.DateTimeField(auto_now=True)

    failure_reason = models.TextField(blank=True)
    idempotency_key = models.CharField(max_length=64, unique=True)
    note = models.TextField(blank=True)

    class Meta:
        constraints = [
            models.CheckConstraint(
                condition=Q(amount__gt=0),
                name='payouts_payout_amount_positive',
            ),
            whole_rupees('amount', 'payouts_payout_amount_whole_rupees'),
            models.CheckConstraint(
                condition=~Q(upi_id_snapshot=''),
                name='payouts_payout_destination_snapshot_required',
            ),
            models.CheckConstraint(
                condition=~Q(status='PAID') | Q(paid_at__isnull=False),
                name='payouts_payout_paid_at_when_paid',
            ),
            models.CheckConstraint(
                condition=~Q(status='PAID') | ~Q(utr=''),
                name='payouts_payout_utr_when_paid',
            ),
            models.CheckConstraint(
                condition=Q(utr='') | Q(utr__regex=UTR_PATTERN),
                name='payouts_payout_utr_12_digits',
            ),
            # One bank transfer can back at most one PAID payout.
            models.UniqueConstraint(
                fields=['utr'],
                condition=Q(status='PAID') & ~Q(utr=''),
                name='payouts_payout_utr_unique_when_paid',
            ),
        ]
        indexes = [
            models.Index(fields=['order', 'status']),
            models.Index(fields=['status', 'created_at']),
        ]

    def save(self, *args, **kwargs):
        if self._state.adding:
            order = self.order
            if self.user_id is None:
                self.user_id = order.user_id
            if self.payout_method_id is None:
                self.payout_method_id = order.payout_method_id
            if self.user_id != order.user_id:
                raise ValidationError('Payout user must be the order owner.')
            if self.payout_method_id != order.payout_method_id:
                raise ValidationError('Payout must go to the payout method fixed on the order.')
            # Snapshot always taken from the method, never from caller input.
            method = self.payout_method
            self.upi_id_snapshot = method.upi_id
            self.provider_code_snapshot = method.provider.code
            self.provider_name_snapshot = method.provider.display_name
        super().save(*args, **kwargs)

    def __str__(self):
        return f'Payout {self.pk} for {self.order_id}'
