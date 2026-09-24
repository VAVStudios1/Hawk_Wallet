"""
Pure money calculations for orders. Decimal in, Decimal out - never float.

Hawk Wallet amounts are whole rupees: income is rounded to 0 decimal places
with ROUND_HALF_UP (45.33 -> 45, 45.50 -> 46, 45.66 -> 46). The DB constraint
orders_order_income_rounded_half_up enforces the same rule on every Order.
"""
from decimal import ROUND_HALF_UP, Decimal

WHOLE_RUPEE = Decimal('1')
PERCENT = Decimal('100')


def calculate_income_amount(order_amount, income_rate):
    if not isinstance(order_amount, Decimal) or not isinstance(income_rate, Decimal):
        raise TypeError('Money calculations require Decimal inputs.')
    return (order_amount * income_rate / PERCENT).quantize(WHOLE_RUPEE, rounding=ROUND_HALF_UP)


def calculate_payout_target(order_amount, income_amount):
    if not isinstance(order_amount, Decimal) or not isinstance(income_amount, Decimal):
        raise TypeError('Money calculations require Decimal inputs.')
    return order_amount + income_amount
