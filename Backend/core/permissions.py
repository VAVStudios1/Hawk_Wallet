from rest_framework.permissions import BasePermission

from core.models import User


def is_verified_member(user):
    """
    True for an active, OTP-verified MEMBER - the only role that may manage
    its own payout methods or claim orders. Admin roles are excluded.
    """
    return bool(
        getattr(user, 'is_authenticated', False)
        and user.is_active
        and user.is_verified
        and user.role == User.Role.MEMBER
    )


def is_active_admin(user):
    """
    True for an active ADMIN or SUPER_ADMIN - the roles that may review
    pay-ins. Members are excluded.
    """
    return bool(
        getattr(user, 'is_authenticated', False)
        and user.is_active
        and user.role in (User.Role.ADMIN, User.Role.SUPER_ADMIN)
    )


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
