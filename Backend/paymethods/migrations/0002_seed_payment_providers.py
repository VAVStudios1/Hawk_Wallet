"""
Seed the UPI apps members can save as payout methods. Rows are matched by
their stable `code` (ids differ per database), and an existing row is left
exactly as configured - this only fills in providers that are missing.
"""
from django.db import migrations

PAYOUT_PROVIDERS = (
    # (code, display_name, sort_order)
    ('PHONEPE', 'PhonePe', 10),
    ('GOOGLE_PAY', 'Google Pay', 20),
    ('BHIM', 'BHIM', 30),
    ('MOBIKWIK', 'MobiKwik', 40),
)


def seed_providers(apps, schema_editor):
    PaymentProvider = apps.get_model('paymethods', 'PaymentProvider')
    for code, display_name, sort_order in PAYOUT_PROVIDERS:
        PaymentProvider.objects.get_or_create(
            code=code,
            defaults={
                'display_name': display_name,
                'sort_order': sort_order,
                'enabled_for_payout_methods': True,
                'is_active': True,
            },
        )


class Migration(migrations.Migration):

    dependencies = [
        ('paymethods', '0001_initial'),
    ]

    operations = [
        # Reverse is a no-op on purpose: provider rows may predate this
        # migration or be referenced by payout methods and pay-ins, so a
        # rollback never deletes them.
        migrations.RunPython(seed_providers, migrations.RunPython.noop),
    ]
