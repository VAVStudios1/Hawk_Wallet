class ClaimError(Exception):
    """Base for claim service errors. `code` is stable for API mapping."""

    code = 'claim_error'
    default_message = 'The order could not be claimed.'

    def __init__(self, message=None):
        super().__init__(message or self.default_message)


class ClaimPermissionDenied(ClaimError):
    code = 'permission_denied'
    default_message = 'Only verified members can claim orders.'


class InvalidIdempotencyKey(ClaimError):
    code = 'invalid_idempotency_key'
    default_message = 'A valid idempotency key (1-64 characters) is required.'


class IdempotencyKeyConflict(ClaimError):
    code = 'idempotency_key_conflict'
    default_message = 'This idempotency key was already used for a different claim.'


class OfferNotFound(ClaimError):
    code = 'offer_not_found'
    default_message = 'Offer not found.'


class OfferUnavailable(ClaimError):
    code = 'offer_unavailable'
    default_message = 'This offer is not available right now.'


class OfferSoldOut(ClaimError):
    code = 'offer_sold_out'
    default_message = 'This offer has no remaining quantity.'


class NoActivePayoutMethod(ClaimError):
    code = 'no_active_payout_method'
    default_message = 'Add a payout method before claiming an order.'


class PayoutMethodUnavailable(ClaimError):
    # Covers unknown, inactive and other users' methods alike, so the
    # existence of someone else's method is never revealed.
    code = 'payout_method_unavailable'
    default_message = 'Choose one of your active payout methods.'


class ClaimConflict(ClaimError):
    code = 'conflict'
    default_message = 'The claim could not be completed. Please try again.'


class OrderReadError(Exception):
    """Base for member order/offer read errors. `code` is stable for API mapping."""

    code = 'order_error'
    default_message = 'Orders could not be loaded.'

    def __init__(self, message=None):
        super().__init__(message or self.default_message)


class OrderReadPermissionDenied(OrderReadError):
    code = 'permission_denied'
    default_message = 'Only verified members can view orders.'


class AdminOrderReadPermissionDenied(OrderReadError):
    code = 'permission_denied'
    default_message = 'Only active admins can view all orders.'


class OrderNotFound(OrderReadError):
    # Covers unknown ids and other users' orders alike, so the existence of
    # someone else's order is never revealed.
    code = 'order_not_found'
    default_message = 'Order not found.'


class InvalidOfferFilter(OrderReadError):
    code = 'invalid_amount_filter'
    default_message = 'min_amount and max_amount must be whole rupees (0-9999999999).'


class OfferCreateError(Exception):
    """Base for admin offer-creation errors. `code` is stable for API mapping."""

    code = 'offer_error'
    default_message = 'The offer could not be created.'

    def __init__(self, message=None):
        super().__init__(message or self.default_message)


class OfferCreatePermissionDenied(OfferCreateError):
    code = 'permission_denied'
    default_message = 'Only active admins can create offers.'


class InvalidOfferIdempotencyKey(OfferCreateError):
    code = 'invalid_idempotency_key'
    default_message = 'A valid idempotency key (1-64 characters) is required.'


class OfferIdempotencyKeyConflict(OfferCreateError):
    code = 'idempotency_key_conflict'
    default_message = 'This idempotency key was already used for a different offer.'


class InvalidOffer(OfferCreateError):
    code = 'invalid_offer'
    default_message = 'The offer details are invalid.'


class OfferCreateConflict(OfferCreateError):
    code = 'conflict'
    default_message = 'The offer could not be created. Please try again.'
