from django.db import migrations, models


class Migration(migrations.Migration):
    """Team / referral, step 3 of 3: referral_code required and unique."""

    dependencies = [
        ('core', '0004_backfill_referral_codes'),
    ]

    operations = [
        migrations.AlterField(
            model_name='user',
            name='referral_code',
            field=models.CharField(
                editable=False,
                help_text='Generated on creation; shared in invite links.',
                max_length=8,
                unique=True,
            ),
        ),
        migrations.AddConstraint(
            model_name='user',
            constraint=models.CheckConstraint(
                condition=models.Q(('referral_code__regex', '^[A-HJ-NP-Z2-9]{8}$')),
                name='core_user_referral_code_format',
            ),
        ),
        migrations.AddConstraint(
            model_name='user',
            constraint=models.CheckConstraint(
                condition=models.Q(('referrer', models.F('id')), _negated=True),
                name='core_user_not_self_referred',
            ),
        ),
    ]
