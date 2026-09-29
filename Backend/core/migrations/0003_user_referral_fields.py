import django.db.models.deletion
from django.db import migrations, models


class Migration(migrations.Migration):
    """
    Team / referral, step 1 of 3: add the columns. referral_code starts
    nullable so existing rows stay valid; 0004 fills it and 0005 makes it
    required and unique. referrer stays NULL for every existing user - no
    referral relationship existed before this feature.
    """

    dependencies = [
        ('core', '0002_user_role'),
    ]

    operations = [
        migrations.AddField(
            model_name='user',
            name='referral_code',
            field=models.CharField(editable=False, max_length=8, null=True),
        ),
        migrations.AddField(
            model_name='user',
            name='referrer',
            field=models.ForeignKey(
                blank=True,
                editable=False,
                help_text='Direct referrer, fixed at registration. Empty if none.',
                null=True,
                on_delete=django.db.models.deletion.PROTECT,
                related_name='direct_referrals',
                to='core.user',
            ),
        ),
    ]
