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


class PayoutNotFound(PayoutError):
    code = 'payout_not_found'
    default_message = 'Payout not found.'


class PayoutNotPending(PayoutError):
    code = 'payout_not_pending'
    default_message = 'Only a pending payout can be marked paid.'


class PayoutAlreadyPaid(PayoutError):
    code = 'payout_already_paid'
    default_message = 'This payout was already marked paid with a different UTR.'


class OrderNotInPayout(PayoutError):
    code = 'order_not_in_payout'
    default_message = 'Payouts can only be marked paid while the order payout is in progress.'


class InvalidPayoutUtr(PayoutError):
    code = 'invalid_utr'
    default_message = 'UTR must be exactly 12 digits, with no spaces or other characters.'


class PayoutUtrAlreadyUsed(PayoutError):
    code = 'utr_already_used'
    default_message = 'This UTR is already recorded against another payment.'


class PayoutTotalConflict(PayoutError):
    code = 'payout_total_conflict'
    default_message = 'Marking this payout paid would exceed what was committed on the order.'


class InvalidCancellationReason(PayoutError):
    code = 'invalid_cancellation_reason'
    default_message = 'A cancellation reason (1-500 characters) is required.'


class PayoutNotCancellable(PayoutError):
    code = 'payout_not_cancellable'
    default_message = 'Only a pending payout can be cancelled.'


class PayoutAlreadyCancelled(PayoutError):
    code = 'payout_already_cancelled'
    default_message = 'This payout was already cancelled with a different reason.'


class PayoutQrNotFound(PayoutError):
    code = 'payout_qr_not_found'
    default_message = 'The payout QR image is not available.'
