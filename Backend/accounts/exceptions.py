class ProfileError(Exception):
    """Base for profile errors. `code` is stable for API mapping."""

    code = 'profile_error'
    default_message = 'The profile could not be updated.'

    def __init__(self, message=None, errors=None):
        super().__init__(message or self.default_message)
        self.errors = errors or {}


class ProfilePermissionDenied(ProfileError):
    code = 'permission_denied'
    default_message = 'Only verified users have a profile.'


class InvalidProfileName(ProfileError):
    code = 'invalid_name'
    default_message = 'Name is invalid.'


class IncorrectCurrentPassword(ProfileError):
    code = 'incorrect_current_password'
    default_message = 'Current password is incorrect.'


class InvalidNewPassword(ProfileError):
    code = 'invalid_new_password'
    default_message = 'New password does not meet the password rules.'
