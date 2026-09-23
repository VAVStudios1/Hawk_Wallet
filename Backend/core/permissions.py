from rest_framework.permissions import BasePermission


class IsVerifiedUser(BasePermission):
    """
    Grants access only to authenticated users who have completed OTP
    verification. Use on endpoints that must not be reachable by an account
    stuck mid-registration.
    """

    def has_permission(self, request, view):
        return bool(
            request.user
            and request.user.is_authenticated
            and request.user.is_verified
        )
