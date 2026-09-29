"""
The signed-in user's own profile: read it, change the name, change the
password. Every function acts on `user` (always request.user) and nobody
else. Profile holds no financial data; Home owns the money summary.

The mobile number is the login ID and never changes here; referral fields
are immutable on the model.
"""
from django.contrib.auth.password_validation import validate_password
from django.core.exceptions import ValidationError
from django.db import transaction

from accounts.exceptions import (
    IncorrectCurrentPassword,
    InvalidNewPassword,
    InvalidProfileName,
    ProfilePermissionDenied,
)
from audit.models import AuditEvent
from audit.services import record_audit_event
from core.models import User
from core.permissions import is_verified_user
from core.tokens import issue_token_pair, revoke_all_refresh_tokens

NAME_MAX_LENGTH = User._meta.get_field('name').max_length


def get_profile(*, user):
    _require_verified(user)
    return user


def update_profile_name(*, user, name, ip_address=None):
    """
    Set the user's name (trimmed, non-blank). Changing it may happen any
    number of times; each actual change writes a PROFILE_NAME_CHANGED audit
    event. Saving the same name is a no-op.
    """
    _require_verified(user)
    name = (name or '').strip()
    if not name:
        raise InvalidProfileName('Name is required.', errors={'name': ['Name is required.']})
    if len(name) > NAME_MAX_LENGTH:
        message = f'Name must be at most {NAME_MAX_LENGTH} characters.'
        raise InvalidProfileName(message, errors={'name': [message]})

    with transaction.atomic():
        locked = User.objects.select_for_update().get(pk=user.pk)
        old_name = locked.name
        if old_name == name:
            return locked

        locked.name = name
        locked.save(update_fields=['name'])
        record_audit_event(
            actor=locked,
            action=AuditEvent.Action.PROFILE_NAME_CHANGED,
            target=locked,
            ip_address=ip_address,
            metadata={'old_name': old_name, 'new_name': name},
        )
        return locked


def change_password(*, user, current_password, new_password, ip_address=None):
    """
    Verify the current password, apply Django's password validators to the
    new one, save it, revoke every refresh token the user has (all devices),
    and write a PASSWORD_CHANGED audit event - all in one transaction.
    Returns a fresh token pair so the calling device stays signed in.

    The audit event holds no password, hash or token.
    """
    _require_verified(user)
    with transaction.atomic():
        # The same row lock token rotation takes (core.tokens.lock_user), so
        # no refresh can mint a token the revocation below misses.
        locked = User.objects.select_for_update().get(pk=user.pk)
        if not locked.check_password(current_password):
            raise IncorrectCurrentPassword(
                errors={'current_password': [IncorrectCurrentPassword.default_message]}
            )
        try:
            validate_password(new_password, user=locked)
        except ValidationError as exc:
            raise InvalidNewPassword(errors={'new_password': list(exc.messages)})

        locked.set_password(new_password)
        locked.save(update_fields=['password'])
        revoked = revoke_all_refresh_tokens(locked)
        record_audit_event(
            actor=locked,
            action=AuditEvent.Action.PASSWORD_CHANGED,
            target=locked,
            ip_address=ip_address,
            metadata={'revoked_refresh_tokens': revoked},
        )
        # Issued after the revocation, so this pair is the only live one.
        return issue_token_pair(locked)


def _require_verified(user):
    if not is_verified_user(user):
        raise ProfilePermissionDenied()
