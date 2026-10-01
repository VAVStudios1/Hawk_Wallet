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


class AdminUserError(Exception):
    """Base for admin user-directory errors. `code` is stable for API mapping."""

    code = 'admin_user_error'
    default_message = 'Users could not be loaded.'

    def __init__(self, message=None):
        super().__init__(message or self.default_message)


class AdminUserPermissionDenied(AdminUserError):
    code = 'permission_denied'
    default_message = 'Only active admins can view users.'


class AdminUserNotFound(AdminUserError):
    code = 'user_not_found'
    default_message = 'User not found.'


class InvalidAdminUserQuery(AdminUserError):
    code = 'invalid_query'
    default_message = 'The user list query is invalid.'


class AdminManagementError(Exception):
    """Base for SUPER_ADMIN admin-management errors. `code` is stable for API mapping."""

    code = 'admin_management_error'
    default_message = 'The admin account could not be updated.'

    def __init__(self, message=None, errors=None):
        super().__init__(message or self.default_message)
        self.errors = errors or {}


class AdminManagementPermissionDenied(AdminManagementError):
    code = 'permission_denied'
    default_message = 'Only an active Super Admin can manage admins.'


class AdminAccountNotFound(AdminManagementError):
    code = 'admin_not_found'
    default_message = 'Admin not found.'


class InvalidAdminQuery(AdminManagementError):
    code = 'invalid_query'
    default_message = 'The admin list query is invalid.'


class MobileNumberTaken(AdminManagementError):
    code = 'mobile_number_taken'
    default_message = 'An account with this mobile number already exists.'


class CannotManageOwnAccount(AdminManagementError):
    code = 'cannot_manage_own_account'
    default_message = 'You cannot change the status of your own account.'


class AdminNotManageable(AdminManagementError):
    code = 'admin_not_manageable'
    default_message = 'Only ADMIN accounts can be activated or deactivated here.'
