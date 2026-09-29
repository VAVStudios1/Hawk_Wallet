import secrets

from django.db import migrations

# Frozen copy of core.models.REFERRAL_CODE_* so this migration never changes
# behaviour when the live code does.
ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'
LENGTH = 8


def backfill_referral_codes(apps, schema_editor):
    # Only referral_code is written; referrer is left NULL.
    User = apps.get_model('core', 'User')
    taken = set(
        User.objects.exclude(referral_code__isnull=True).values_list('referral_code', flat=True)
    )
    for pk in User.objects.filter(referral_code__isnull=True).order_by('pk').values_list('pk', flat=True):
        code = ''.join(secrets.choice(ALPHABET) for _ in range(LENGTH))
        while code in taken:
            code = ''.join(secrets.choice(ALPHABET) for _ in range(LENGTH))
        taken.add(code)
        User.objects.filter(pk=pk).update(referral_code=code)


class Migration(migrations.Migration):
    """Team / referral, step 2 of 3: give every existing user a unique code."""

    dependencies = [
        ('core', '0003_user_referral_fields'),
    ]

    operations = [
        migrations.RunPython(backfill_referral_codes, migrations.RunPython.noop),
    ]
