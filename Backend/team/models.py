from django.conf import settings
from django.db import models
from django.db.models import F, Q

from audit.mixins import AppendOnlyModel
from team.calculations import MAX_COMMISSION_LEVEL


class Commission(AppendOnlyModel):
    """
    One team commission: `beneficiary` earned `amount` because `source_user`'s
    pay-in `payin` was confirmed, `level` steps below the beneficiary in the
    referral tree. This table is the commission history; totals are derived
    from it, never stored separately.

    Written only by team.services.generate_pay_in_commissions, inside the
    pay-in confirmation transaction, and never edited or deleted.
    rate is the percentage applied (0.30 = 0.3%) and base_amount the
    confirmed pay-in amount it was applied to, both kept as used.
    """

    beneficiary = models.ForeignKey(
        settings.AUTH_USER_MODEL,
        on_delete=models.PROTECT,
        related_name='commissions_earned',
    )
    source_user = models.ForeignKey(
        settings.AUTH_USER_MODEL,
        on_delete=models.PROTECT,
        related_name='commissions_generated',
    )
    payin = models.ForeignKey(
        'payins.PayIn',
        on_delete=models.PROTECT,
        related_name='commissions',
    )
    level = models.PositiveSmallIntegerField()
    rate = models.DecimalField(max_digits=6, decimal_places=4, help_text='Percent, e.g. 0.3000 = 0.3%.')
    base_amount = models.DecimalField(max_digits=12, decimal_places=2)
    amount = models.DecimalField(max_digits=12, decimal_places=2)
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        ordering = ['-created_at', '-id']
        constraints = [
            # Idempotency: one commission per level per pay-in, and a
            # beneficiary can appear only once in a pay-in's ancestor chain.
            models.UniqueConstraint(
                fields=['payin', 'level'],
                name='team_commission_unique_payin_level',
            ),
            models.UniqueConstraint(
                fields=['payin', 'beneficiary'],
                name='team_commission_unique_payin_beneficiary',
            ),
            models.CheckConstraint(
                condition=Q(level__gte=1) & Q(level__lte=MAX_COMMISSION_LEVEL),
                name='team_commission_level_in_range',
            ),
            models.CheckConstraint(
                condition=Q(amount__gt=0),
                name='team_commission_amount_positive',
            ),
            models.CheckConstraint(
                condition=Q(rate__gt=0),
                name='team_commission_rate_positive',
            ),
            models.CheckConstraint(
                condition=Q(base_amount__gt=0),
                name='team_commission_base_amount_positive',
            ),
            models.CheckConstraint(
                condition=~Q(beneficiary=F('source_user')),
                name='team_commission_not_self',
            ),
        ]
        indexes = [
            models.Index(fields=['beneficiary', 'created_at']),
            models.Index(fields=['beneficiary', 'level']),
        ]

    def __str__(self):
        return f'Commission L{self.level} {self.amount} to {self.beneficiary_id} (PayIn {self.payin_id})'
