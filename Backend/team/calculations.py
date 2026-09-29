"""
Pure team-commission calculations. Decimal in, Decimal out - never float.

Rates are percentages of the confirmed pay-in amount: level 1 (the payer's
direct referrer) earns 0.3%, levels 2-21 earn 0.1% each, and level 22 and
deeper earn nothing.

Commission is rounded to paise (2 decimal places) with ROUND_HALF_UP
(1.555 -> 1.56, 0.405 -> 0.41). Unlike order income it is NOT rounded to
whole rupees (user decision 2026-09-29).
"""
from decimal import ROUND_HALF_UP, Decimal

MAX_COMMISSION_LEVEL = 21
PAISE = Decimal('0.01')
PERCENT = Decimal('100')

LEVEL_1_RATE = Decimal('0.30')
DEEP_LEVEL_RATE = Decimal('0.10')


def commission_rate(level):
    """Percentage rate for `level`, or None when the level earns nothing."""
    if not isinstance(level, int) or level < 1 or level > MAX_COMMISSION_LEVEL:
        return None
    return LEVEL_1_RATE if level == 1 else DEEP_LEVEL_RATE


def calculate_commission(base_amount, rate):
    if not isinstance(base_amount, Decimal) or not isinstance(rate, Decimal):
        raise TypeError('Money calculations require Decimal inputs.')
    return (base_amount * rate / PERCENT).quantize(PAISE, rounding=ROUND_HALF_UP)
