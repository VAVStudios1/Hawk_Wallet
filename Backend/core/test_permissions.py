import pytest
from django.contrib.auth.models import AnonymousUser

from core.models import User
from core.permissions import is_active_admin, is_verified_member


# (user flags, is_verified_member, is_active_admin)
MATRIX = [
    ({'role': User.Role.MEMBER}, True, False),
    ({'role': User.Role.MEMBER, 'is_verified': False}, False, False),
    ({'role': User.Role.MEMBER, 'is_active': False}, False, False),
    ({'role': User.Role.ADMIN}, False, True),
    ({'role': User.Role.ADMIN, 'is_active': False}, False, False),
    # Admins are not gated on OTP verification.
    ({'role': User.Role.ADMIN, 'is_verified': False}, False, True),
    ({'role': User.Role.SUPER_ADMIN}, False, True),
    ({'role': User.Role.SUPER_ADMIN, 'is_active': False}, False, False),
    ({'role': User.Role.SUPER_ADMIN, 'is_verified': False}, False, True),
]


@pytest.mark.parametrize('flags, member, admin', MATRIX)
def test_permission_helpers(make_user, flags, member, admin):
    user = make_user(**flags)
    assert is_verified_member(user) is member
    assert is_active_admin(user) is admin


@pytest.mark.parametrize('user', [AnonymousUser(), None, object()])
def test_anonymous_or_missing_user_has_no_role(user):
    assert is_verified_member(user) is False
    assert is_active_admin(user) is False


def test_member_and_admin_are_mutually_exclusive(make_user):
    # No role passes both checks, so member-only and admin-only services
    # can never be reached by the same account.
    for role in User.Role.values:
        user = make_user(role=role)
        assert not (is_verified_member(user) and is_active_admin(user))
