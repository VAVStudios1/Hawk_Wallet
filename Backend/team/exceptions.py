class ReferralError(Exception):
    """Base for referral assignment errors. `code` is stable for API mapping."""

    code = 'referral_error'
    default_message = 'The referral code could not be applied.'

    def __init__(self, message=None):
        super().__init__(message or self.default_message)


class InvalidReferralCode(ReferralError):
    code = 'invalid_referral_code'
    default_message = 'This referral code is not valid.'


class SelfReferral(ReferralError):
    code = 'self_referral'
    default_message = 'You cannot use your own referral code.'


class TeamError(Exception):
    """Base for team read service errors. `code` is stable for API mapping."""

    code = 'team_error'
    default_message = 'Team information is not available.'

    def __init__(self, message=None):
        super().__init__(message or self.default_message)


class TeamPermissionDenied(TeamError):
    code = 'permission_denied'
    default_message = 'Only verified members have a team.'


class InvalidTeamLevel(TeamError):
    code = 'invalid_level'
    default_message = 'Level must be a whole number of 1 or more.'


class InvalidPage(TeamError):
    code = 'invalid_page'
    default_message = 'Page must be a whole number from 1 up to the last page.'


class CommissionError(Exception):
    """Commission generation invariant failed; the confirmation rolls back."""
