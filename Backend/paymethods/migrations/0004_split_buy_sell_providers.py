"""
Split the provider catalog into PERSONAL / BUY and BUSINESS / SELL.

The four providers seeded by 0002 become PERSONAL / BUY: they can no longer
be picked for NEW payout methods, but existing UserPayoutMethods on them are
untouched and keep working (the flags are only checked when a method is
added). Three BUSINESS / SELL providers are added. Rows are matched by
their stable `code`; no row is renamed or deleted.
"""
from django.db import migrations

PERSONAL_BUY_CODES = ('PHONEPE', 'GOOGLE_PAY', 'BHIM', 'MOBIKWIK')

BUSINESS_SELL_PROVIDERS = (
    # (code, display_name, sort_order)
    ('BHARATPE', 'BharatPe', 110),
    ('PAYTM_BUSINESS', 'Paytm Business', 120),
    ('PHONEPE_BUSINESS', 'PhonePe Business', 130),
)


def split_providers(apps, schema_editor):
    PaymentProvider = apps.get_model('paymethods', 'PaymentProvider')
    # Each row is turned off for one side before being turned on for the
    # other, so the "not both BUY and SELL" constraint holds at every step.
    for provider in PaymentProvider.objects.filter(code__in=PERSONAL_BUY_CODES):
        provider.enabled_for_payout_methods = False
        provider.save(update_fields=['enabled_for_payout_methods'])
        provider.enabled_for_buy_methods = True
        provider.save(update_fields=['enabled_for_buy_methods'])

    for code, display_name, sort_order in BUSINESS_SELL_PROVIDERS:
        provider, created = PaymentProvider.objects.get_or_create(
            code=code,
            defaults={
                'display_name': display_name,
                'sort_order': sort_order,
                'enabled_for_payout_methods': True,
                'enabled_for_buy_methods': False,
                'enabled_for_pay_in': False,
                'is_active': True,
            },
        )
        if not created:
            provider.enabled_for_buy_methods = False
            provider.save(update_fields=['enabled_for_buy_methods'])
            provider.enabled_for_payout_methods = True
            provider.save(update_fields=['enabled_for_payout_methods'])


def unsplit_providers(apps, schema_editor):
    # Restores the 0002 flags. Business rows stay (methods may reference
    # them with PROTECT) but are no longer offered for new methods.
    PaymentProvider = apps.get_model('paymethods', 'PaymentProvider')
    for provider in PaymentProvider.objects.filter(code__in=PERSONAL_BUY_CODES):
        provider.enabled_for_buy_methods = False
        provider.save(update_fields=['enabled_for_buy_methods'])
        provider.enabled_for_payout_methods = True
        provider.save(update_fields=['enabled_for_payout_methods'])
    business_codes = [code for code, _, _ in BUSINESS_SELL_PROVIDERS]
    for provider in PaymentProvider.objects.filter(code__in=business_codes):
        provider.enabled_for_payout_methods = False
        provider.save(update_fields=['enabled_for_payout_methods'])


class Migration(migrations.Migration):

    dependencies = [
        ('paymethods', '0003_buy_methods'),
    ]

    operations = [
        migrations.RunPython(split_providers, unsplit_providers),
    ]
