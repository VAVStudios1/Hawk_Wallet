class PayInError(Exception):
    """Base for pay-in service errors. `code` is stable for API mapping."""

    code = 'payin_error'
    default_message = 'The payment could not be prepared.'

    def __init__(self, message=None):
        super().__init__(message or self.default_message)


class PayInPermissionDenied(PayInError):
    code = 'permission_denied'
    default_message = 'Only verified members can pay for orders.'


class PayInOrderNotFound(PayInError):
    # Covers unknown ids and other users' orders alike, so the existence of
    # someone else's order is never revealed.
    code = 'order_not_found'
    default_message = 'Order not found.'


class OrderNotPayable(PayInError):
    code = 'order_not_payable'
    default_message = 'This order is not awaiting payment.'


class NoReceivingAccount(PayInError):
    code = 'no_receiving_account'
    default_message = 'Payments are temporarily unavailable. Please try again later.'


class InvalidPayInIntent(PayInError):
    code = 'invalid_pay_in_intent'
    default_message = 'This payment request is not valid for this order. Please start the payment again.'


class InvalidUtr(PayInError):
    code = 'invalid_utr'
    default_message = 'UTR must be exactly 12 digits, with no spaces or other characters.'


class InvalidIdempotencyKey(PayInError):
    code = 'invalid_idempotency_key'
    default_message = 'A valid idempotency key (1-64 characters) is required.'


class IdempotencyKeyConflict(PayInError):
    code = 'idempotency_key_conflict'
    default_message = 'This idempotency key was already used for a different submission.'


class UtrAlreadySubmitted(PayInError):
    code = 'utr_already_submitted'
    default_message = 'This UTR has already been submitted.'


class PayerAppUnavailable(PayInError):
    code = 'payer_app_unavailable'
    default_message = 'Choose one of the available UPI apps.'


class BuyMethodRequired(PayInError):
    code = 'buy_method_required'
    default_message = 'Choose the UPI account you paid from.'


class BuyMethodUnavailable(PayInError):
    # Covers unknown ids, inactive methods and other users' methods alike.
    code = 'buy_method_unavailable'
    default_message = 'Choose one of your active UPI accounts you paid from.'


class PayInConflict(PayInError):
    code = 'conflict'
    default_message = 'The payment could not be submitted. Please try again.'


class ReviewPermissionDenied(PayInError):
    code = 'permission_denied'
    default_message = 'Only active admins can review pay-ins.'


class PayInNotFound(PayInError):
    code = 'payin_not_found'
    default_message = 'Pay-in not found.'


class PayInAlreadyReviewed(PayInError):
    code = 'payin_already_reviewed'
    default_message = 'This pay-in has already been confirmed or rejected.'


class InvalidRejectionReason(PayInError):
    code = 'invalid_rejection_reason'
    default_message = 'A rejection reason (1-500 characters) is required.'
