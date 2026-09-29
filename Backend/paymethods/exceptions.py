class PayoutMethodError(Exception):
    """Base for payout-method service errors. `code` is stable for API mapping."""

    code = 'payout_method_error'
    default_message = 'Payout method request failed.'

    def __init__(self, message=None, *, errors=None):
        super().__init__(message or self.default_message)
        self.errors = errors or {}


class PayoutMethodPermissionDenied(PayoutMethodError):
    code = 'permission_denied'
    default_message = 'Only verified members can manage payout methods.'


class PayoutMethodNotFound(PayoutMethodError):
    # Also used for other users' methods, so existence is never revealed.
    code = 'not_found'
    default_message = 'Payout method not found.'


class InvalidPayoutMethod(PayoutMethodError):
    code = 'invalid'
    default_message = 'Payout method details are invalid.'


class DuplicatePayoutMethod(PayoutMethodError):
    code = 'duplicate_upi_id'
    default_message = 'You already have an active payout method with this UPI ID.'


class PayoutMethodInactive(PayoutMethodError):
    code = 'inactive'
    default_message = 'This payout method is no longer active.'


class PayoutMethodConflict(PayoutMethodError):
    code = 'conflict'
    default_message = 'Your payout methods changed at the same time. Please try again.'


class BuyMethodError(Exception):
    """Base for buy-method (PERSONAL / BUY) service errors. `code` is stable for API mapping."""

    code = 'buy_method_error'
    default_message = 'Buy method request failed.'

    def __init__(self, message=None, *, errors=None):
        super().__init__(message or self.default_message)
        self.errors = errors or {}


class BuyMethodPermissionDenied(BuyMethodError):
    code = 'permission_denied'
    default_message = 'Only verified members can manage buy methods.'


class BuyMethodNotFound(BuyMethodError):
    # Also used for other users' methods, so existence is never revealed.
    code = 'not_found'
    default_message = 'Buy method not found.'


class InvalidBuyMethod(BuyMethodError):
    code = 'invalid'
    default_message = 'Buy method details are invalid.'


class DuplicateBuyMethod(BuyMethodError):
    code = 'duplicate_upi_id'
    default_message = 'You already have an active buy method with this UPI ID.'


class BuyMethodInactive(BuyMethodError):
    code = 'inactive'
    default_message = 'This buy method is no longer active.'


class BuyMethodConflict(BuyMethodError):
    code = 'conflict'
    default_message = 'Your buy methods changed at the same time. Please try again.'
