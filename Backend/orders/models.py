import secrets
from decimal import Decimal

from django.conf import settings
from django.db import models
from django.db.models import F, Q
from django.db.models.functions import Round
from django.db.models.lookups import Exact, GreaterThanOrEqual, LessThan
from django.utils import timezone

from audit.mixins import ImmutableFieldsMixin, UndeletableModel, whole_rupees
from paymethods.validators import upi_id_validator

# Crockford base32 (no I, L, O, U): unambiguous when read aloud or retyped.
REFERENCE_ALPHABET = '0123456789ABCDEFGHJKMNPQRSTVWXYZ'
REFERENCE_PREFIX = 'HW-'
REFERENCE_LENGTH = 10


def generate_order_reference():
    # Random, not sequential: public references must not leak order volume.
    return REFERENCE_PREFIX + ''.join(
        secrets.choice(REFERENCE_ALPHABET) for _ in range(REFERENCE_LENGTH)
    )


class Currency(models.TextChoices):
    INR = 'INR', 'Indian Rupee'


class OrderOffer(models.Model):
    """
    An offer a member can claim. The claim service snapshots order_amount and
    income_rate onto the Order; later edits here never touch claimed orders.
    Amounts are whole rupees.
    """

    currency = models.CharField(max_length=3, choices=Currency.choices, default=Currency.INR)
    order_amount = models.DecimalField(max_digits=12, decimal_places=2)
    income_rate = models.DecimalField(
        max_digits=5,
        decimal_places=2,
        help_text='Income percentage of order_amount, 0.00-100.00; 4.00 means 4%.',
    )
    is_active = models.BooleanField(default=False)
    valid_from = models.DateTimeField(null=True, blank=True)
    valid_to = models.DateTimeField(null=True, blank=True)
    quantity_available = models.PositiveIntegerField(
        null=True,
        blank=True,
        help_text='Remaining claimable orders; empty means no quantity limit.',
    )
    # Offers created through the admin API carry the decoded text of the
    # uploaded Pay-In QR, and pay_in_upi_id is that QR's pa. Offers from
    # before the QR upload may have only pay_in_upi_id; offers with neither
    # (older, or made outside the API) fall back to the platform receiving
    # account.
    pay_in_qr_payload = models.TextField(
        blank=True,
        default='',
        help_text='Decoded upi://pay text of the Pay-In QR the admin uploaded.',
    )
    pay_in_upi_id = models.CharField(
        max_length=255,
        blank=True,
        default='',
        validators=[upi_id_validator],
        help_text='UPI ID members pay for orders claimed from this offer.',
    )

    # Set when an admin creates the offer through the API; empty for offers
    # made elsewhere (e.g. Django admin, fixtures).
    created_by = models.ForeignKey(
        settings.AUTH_USER_MODEL,
        on_delete=models.PROTECT,
        null=True,
        blank=True,
        related_name='+',
    )
    idempotency_key = models.CharField(max_length=64, null=True, blank=True)

    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        constraints = [
            # A retried API create can never add a second offer. NULL keys
            # (offers not made through the API) are exempt.
            models.UniqueConstraint(
                fields=['idempotency_key'],
                condition=Q(idempotency_key__isnull=False),
                name='orders_offer_unique_idempotency_key',
            ),
            models.CheckConstraint(
                condition=Q(order_amount__gt=0),
                name='orders_offer_order_amount_positive',
            ),
            models.CheckConstraint(
                condition=Q(income_rate__gte=0, income_rate__lte=100),
                name='orders_offer_income_rate_0_to_100',
            ),
            whole_rupees('order_amount', 'orders_offer_order_amount_whole_rupees'),
            models.CheckConstraint(
                condition=(
                    Q(valid_from__isnull=True)
                    | Q(valid_to__isnull=True)
                    | Q(valid_to__gt=F('valid_from'))
                ),
                name='orders_offer_valid_window',
            ),
        ]

    def __str__(self):
        return f'{self.currency} {self.order_amount} @ {self.income_rate}%'


class Order(ImmutableFieldsMixin, UndeletableModel):
    """
    A member's claimed offer. At claim time the order snapshots order_amount,
    income_rate and the Pay-In destination (pay_in_upi_id, pay_in_qr_payload)
    from the offer, income_amount is calculated once (whole rupees,
    ROUND_HALF_UP - see orders.calculations), and
    payout_target = order_amount + income_amount. None of these ever change
    afterwards. Payout progress is tracked by the two running totals, which
    the DB constrains to never exceed payout_target.
    """

    class Status(models.TextChoices):
        PAYMENT_REQUIRED = 'PAYMENT_REQUIRED', 'Payment required'
        PAYMENT_UNDER_REVIEW = 'PAYMENT_UNDER_REVIEW', 'Payment under review'
        ACTIVE = 'ACTIVE', 'Active'
        PAYOUT_IN_PROGRESS = 'PAYOUT_IN_PROGRESS', 'Payout in progress'
        COMPLETED = 'COMPLETED', 'Completed'
        EXPIRED = 'EXPIRED', 'Expired'
        CANCELLED = 'CANCELLED', 'Cancelled'

    immutable_fields = (
        'reference',
        'user',
        'offer',
        'currency',
        'order_amount',
        'income_rate',
        'income_amount',
        'payout_target',
        'payout_method',
        'pay_in_upi_id',
        'pay_in_qr_payload',
        'idempotency_key',
        'claimed_at',
    )

    reference = models.CharField(
        max_length=16,
        unique=True,
        default=generate_order_reference,
        editable=False,
    )
    user = models.ForeignKey(
        settings.AUTH_USER_MODEL,
        on_delete=models.PROTECT,
        related_name='orders',
    )
    offer = models.ForeignKey(OrderOffer, on_delete=models.PROTECT, related_name='orders')

    currency = models.CharField(max_length=3, choices=Currency.choices)
    order_amount = models.DecimalField(
        max_digits=12,
        decimal_places=2,
        help_text='What the member pays the platform.',
    )
    income_rate = models.DecimalField(
        max_digits=5,
        decimal_places=2,
        help_text='Snapshot of the offer income percentage at claim time.',
    )
    income_amount = models.DecimalField(
        max_digits=12,
        decimal_places=2,
        help_text='order_amount x income_rate / 100, rounded half-up to whole rupees at claim time.',
    )
    payout_target = models.DecimalField(
        max_digits=12,
        decimal_places=2,
        help_text='Total the platform owes the member: order_amount + income_amount.',
    )

    payout_method = models.ForeignKey(
        'paymethods.UserPayoutMethod',
        on_delete=models.PROTECT,
        related_name='orders',
        help_text='Fixed at claim time; later default changes never affect this order.',
    )
    pay_in_upi_id = models.CharField(
        max_length=255,
        blank=True,
        default='',
        help_text=(
            "Snapshot of the offer's Pay-In UPI at claim time: where the member "
            'pays for this order. Never follows later offer edits. Empty on '
            'orders claimed before offers carried a Pay-In UPI.'
        ),
    )
    pay_in_qr_payload = models.TextField(
        blank=True,
        default='',
        help_text=(
            "Snapshot of the offer's decoded Pay-In QR at claim time; the pay-in "
            'URI is built from it. Empty on orders claimed before QR upload.'
        ),
    )
    status = models.CharField(
        max_length=32,
        choices=Status.choices,
        default=Status.PAYMENT_REQUIRED,
    )

    payout_committed_total = models.DecimalField(
        max_digits=12,
        decimal_places=2,
        default=Decimal('0.00'),
        help_text='Sum of PENDING + PAID payouts.',
    )
    payout_paid_total = models.DecimalField(
        max_digits=12,
        decimal_places=2,
        default=Decimal('0.00'),
        help_text='Sum of PAID payouts.',
    )

    idempotency_key = models.CharField(max_length=64)

    claimed_at = models.DateTimeField(default=timezone.now)
    expires_at = models.DateTimeField(null=True, blank=True)
    activated_at = models.DateTimeField(null=True, blank=True)
    completed_at = models.DateTimeField(null=True, blank=True)

    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        constraints = [
            models.CheckConstraint(
                condition=Q(order_amount__gt=0),
                name='orders_order_order_amount_positive',
            ),
            models.CheckConstraint(
                condition=Q(income_rate__gte=0, income_rate__lte=100),
                name='orders_order_income_rate_0_to_100',
            ),
            models.CheckConstraint(
                condition=Q(income_amount__gte=0),
                name='orders_order_income_amount_non_negative',
            ),
            # Compared in whole paise: exact on PostgreSQL numeric, and immune
            # to SQLite storing decimals as floats in dev.
            models.CheckConstraint(
                condition=Exact(
                    Round(F('payout_target') * 100),
                    Round(F('order_amount') * 100) + Round(F('income_amount') * 100),
                ),
                name='orders_order_target_is_amount_plus_income',
            ),
            whole_rupees('order_amount', 'orders_order_order_amount_whole_rupees'),
            whole_rupees('income_amount', 'orders_order_income_amount_whole_rupees'),
            whole_rupees('payout_target', 'orders_order_payout_target_whole_rupees'),
            whole_rupees('payout_committed_total', 'orders_order_committed_total_whole_rupees'),
            whole_rupees('payout_paid_total', 'orders_order_paid_total_whole_rupees'),
            # income_amount == ROUND_HALF_UP(order_amount * income_rate / 100),
            # written as |order_amount * rate_bp - income_amount * 10000| in
            # [-5000, 5000) where rate_bp is the rate in basis points. Pure
            # integer arithmetic: exact on PostgreSQL and on SQLite's floats.
            models.CheckConstraint(
                condition=GreaterThanOrEqual(
                    F('order_amount') * Round(F('income_rate') * 100),
                    F('income_amount') * 10000 - 5000,
                ),
                name='orders_order_income_rounded_half_up_lower',
            ),
            models.CheckConstraint(
                condition=LessThan(
                    F('order_amount') * Round(F('income_rate') * 100),
                    F('income_amount') * 10000 + 5000,
                ),
                name='orders_order_income_rounded_half_up_upper',
            ),
            models.CheckConstraint(
                condition=Q(payout_target__gt=0),
                name='orders_order_payout_target_positive',
            ),
            models.CheckConstraint(
                condition=Q(payout_paid_total__gte=0),
                name='orders_order_paid_total_non_negative',
            ),
            models.CheckConstraint(
                condition=Q(payout_paid_total__lte=F('payout_committed_total')),
                name='orders_order_paid_lte_committed',
            ),
            models.CheckConstraint(
                condition=Q(payout_committed_total__lte=F('payout_target')),
                name='orders_order_committed_lte_target',
            ),
            models.CheckConstraint(
                condition=~Q(status='COMPLETED') | Q(payout_paid_total=F('payout_target')),
                name='orders_order_completed_fully_paid',
            ),
            models.UniqueConstraint(
                fields=['user', 'idempotency_key'],
                name='orders_order_unique_idempotency_key_per_user',
            ),
        ]
        indexes = [
            models.Index(fields=['user', 'status']),
            models.Index(fields=['status', 'created_at']),
        ]

    def __str__(self):
        return self.reference
