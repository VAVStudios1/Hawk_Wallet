from rest_framework.permissions import BasePermission

from core.models import User


def is_verified_user(user):
    """
    True for an active, OTP-verified user of any role - the rule behind
    IsVerifiedUser, for services that must enforce it themselves.
    """
    return bool(
        getattr(user, 'is_authenticated', False)
        and user.is_active
        and user.is_verified
    )


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
    Grants access only to active, authenticated users who have completed OTP
    verification, whatever their role. Use on endpoints that must not be
    reachable by an account stuck mid-registration. Member-only endpoints
    use IsVerifiedMember instead.
    """

    def has_permission(self, request, view):
        return is_verified_user(request.user)


class IsVerifiedMember(BasePermission):
    """API gate for member-only endpoints; same rule as is_verified_member()."""

    def has_permission(self, request, view):
        return is_verified_member(request.user)


class IsActiveAdmin(BasePermission):
    """API gate for admin-only endpoints; same rule as is_active_admin()."""

    def has_permission(self, request, view):
        return is_active_admin(request.user)


def is_super_admin(user):
    """True for an active SUPER_ADMIN - the only role that may manage admins and read the audit log."""
    return bool(
        getattr(user, 'is_authenticated', False)
        and user.is_active
        and user.role == User.Role.SUPER_ADMIN
    )


class IsSuperAdmin(BasePermission):
    """API gate for SUPER_ADMIN-only endpoints; same rule as is_super_admin()."""

    def has_permission(self, request, view):
        return is_super_admin(request.user)
