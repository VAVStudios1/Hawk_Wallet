class PayoutError(Exception):
    """Base for payout service errors. `code` is stable for API mapping."""

    code = 'payout_error'
    default_message = 'The payout could not be created.'

    def __init__(self, message=None):
        super().__init__(message or self.default_message)


class PayoutPermissionDenied(PayoutError):
    code = 'permission_denied'
    default_message = 'Only active admins can create payouts.'


class InvalidIdempotencyKey(PayoutError):
    code = 'invalid_idempotency_key'
    default_message = 'A valid idempotency key (1-64 characters) is required.'


class IdempotencyKeyConflict(PayoutError):
    code = 'idempotency_key_conflict'
    default_message = 'This idempotency key was already used for a different payout.'


class InvalidPayoutAmount(PayoutError):
    code = 'invalid_amount'
    default_message = 'Payout amount must be a positive whole-rupee amount.'


class PayoutOrderNotFound(PayoutError):
    code = 'order_not_found'
    default_message = 'Order not found.'


class OrderNotPayable(PayoutError):
    code = 'order_not_payable'
    default_message = 'Payouts can only be created for orders whose payment is confirmed.'


class PayoutExceedsRemaining(PayoutError):
    code = 'payout_exceeds_remaining'
    default_message = 'This payout would exceed the amount still owed on the order.'


class PayoutConflict(PayoutError):
    code = 'conflict'
    default_message = 'The payout could not be created. Please try again.'
