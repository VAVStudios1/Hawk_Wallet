from django.conf import settings
from django.db import models
from django.db.models import F, Q
from django.db.models.functions import Lower

from audit.mixins import ImmutableFieldsMixin
from paymethods.storage import get_private_storage
from paymethods.validators import (
    merchant_code_validator,
    payout_qr_upload_to,
    platform_qr_upload_to,
    provider_code_validator,
    qr_extension_validator,
    upi_id_validator,
    validate_qr_image,
)


class Rail(models.TextChoices):
    UPI = 'UPI', 'UPI'


class PaymentProvider(models.Model):
    """
    Admin-managed catalog entry (PhonePe, Google Pay, Paytm, BHIM, Other UPI).
    Disable instead of deleting: financial records reference providers with
    PROTECT, so a disabled provider never breaks history.
    """

    code = models.CharField(max_length=32, unique=True, validators=[provider_code_validator])
    display_name = models.CharField(max_length=64)
    logo_url = models.URLField(blank=True)
    rail = models.CharField(max_length=16, choices=Rail.choices, default=Rail.UPI)
    android_package = models.CharField(
        max_length=255,
        blank=True,
        help_text='Optional Android package name used to target this UPI app.',
    )
    enabled_for_payout_methods = models.BooleanField(default=False)
    enabled_for_pay_in = models.BooleanField(default=False)
    sort_order = models.PositiveSmallIntegerField(default=0)
    is_active = models.BooleanField(default=True)

    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        ordering = ['sort_order', 'display_name']

    def __str__(self):
        return self.display_name


class UserPayoutMethod(ImmutableFieldsMixin, models.Model):
    """
    The user's own destination for money the platform sends to them.
    Private: visible to the owner, and to admins only via an order/payout.

    Once an order or payout references a method, its destination (provider,
    UPI ID, QR) is frozen; changing it means creating a new method and
    deactivating this one.
    """

    immutable_fields = ('user', 'provider', 'upi_id', 'qr_image')

    user = models.ForeignKey(
        settings.AUTH_USER_MODEL,
        on_delete=models.PROTECT,
        related_name='payout_methods',
    )
    provider = models.ForeignKey(
        PaymentProvider,
        on_delete=models.PROTECT,
        related_name='user_payout_methods',
    )
    label = models.CharField(max_length=64, blank=True)
    upi_id = models.CharField(max_length=255, validators=[upi_id_validator])
    qr_image = models.ImageField(
        upload_to=payout_qr_upload_to,
        storage=get_private_storage,
        max_length=255,
        validators=[qr_extension_validator, validate_qr_image],
    )
    qr_sha256 = models.CharField(
        max_length=64,
        blank=True,
        help_text='SHA-256 of the stored QR file; set by the upload service.',
    )
    is_default = models.BooleanField(default=False)
    is_active = models.BooleanField(default=True)

    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        constraints = [
            models.CheckConstraint(
                condition=Q(is_active=True) | Q(is_default=False),
                name='paymethods_upm_default_must_be_active',
            ),
            models.UniqueConstraint(
                fields=['user'],
                condition=Q(is_default=True),
                name='paymethods_upm_one_default_per_user',
            ),
            # UPI IDs are case-insensitive, so compare them lower-cased.
            models.UniqueConstraint(
                F('user'),
                Lower('upi_id'),
                condition=Q(is_active=True),
                name='paymethods_upm_unique_active_upi_per_user',
            ),
            models.CheckConstraint(
                condition=~Q(qr_image=''),
                name='paymethods_upm_qr_image_required',
            ),
        ]
        indexes = [
            models.Index(fields=['user', 'is_active']),
        ]

    def immutability_applies(self):
        return self.orders.exists() or self.payouts.exists()

    def __str__(self):
        return f'{self.provider} ({self.pk})'


class PlatformReceivingAccount(models.Model):
    """
    Where users' pay-ins go. Platform configuration, NOT a user payout method.
    Never exposed to members through a general API; the pay-in intent for a
    specific order carries only what that payment needs.
    """

    provider = models.ForeignKey(
        PaymentProvider,
        on_delete=models.PROTECT,
        related_name='receiving_accounts',
    )
    payee_vpa = models.CharField(max_length=255, validators=[upi_id_validator])
    payee_name = models.CharField(max_length=100)
    merchant_code = models.CharField(
        max_length=4,
        blank=True,
        validators=[merchant_code_validator],
        help_text='UPI merchant category code (mc), if this is a merchant VPA.',
    )
    qr_image = models.ImageField(
        upload_to=platform_qr_upload_to,
        storage=get_private_storage,
        max_length=255,
        blank=True,
        validators=[qr_extension_validator, validate_qr_image],
    )
    is_active = models.BooleanField(default=False)
    priority = models.PositiveSmallIntegerField(
        default=0,
        help_text='Ordering hint among active accounts; lower comes first.',
    )
    created_by = models.ForeignKey(
        settings.AUTH_USER_MODEL,
        on_delete=models.PROTECT,
        null=True,
        blank=True,
        related_name='+',
    )

    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        ordering = ['priority', 'id']

    def __str__(self):
        return f'{self.payee_name} ({self.provider})'
