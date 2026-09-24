"""
Member-facing payout method services.

Every state change runs in transaction.atomic() and first locks the member's
User row, so concurrent requests for one member are serialized on
PostgreSQL. The DB constraints (one default per user, no duplicate active
UPI ID, default must be active) remain the final guard; a violation that
slips past the application checks surfaces as PayoutMethodConflict.

There is deliberately no "edit" service: a different UPI ID or QR means a
new method plus deactivating the old one.
"""
from django.contrib.auth import get_user_model
from django.core.exceptions import ValidationError
from django.db import IntegrityError, transaction

from audit.models import AuditEvent
from audit.services import record_audit_event
from core.permissions import is_verified_member
from paymethods.exceptions import (
    DuplicatePayoutMethod,
    InvalidPayoutMethod,
    PayoutMethodConflict,
    PayoutMethodInactive,
    PayoutMethodNotFound,
    PayoutMethodPermissionDenied,
)
from paymethods.models import PaymentProvider, UserPayoutMethod
from paymethods.qr import sanitize_qr_image
from paymethods.validators import upi_id_validator

User = get_user_model()

# When the default method is deactivated, the newest remaining active method
# becomes the default (most likely the replacement the member just added).
REPLACEMENT_ORDER = ('-created_at', '-id')


def add_payout_method(
    *, user, provider_id, upi_id, qr_image, label='', make_default=False, ip_address=None
):
    _require_member(user)
    provider = _get_available_provider(provider_id)
    try:
        upi_id_validator(upi_id)
    except ValidationError as exc:
        raise InvalidPayoutMethod(errors={'upi_id': exc.messages})
    try:
        qr = sanitize_qr_image(qr_image)
    except ValidationError as exc:
        raise InvalidPayoutMethod(errors={'qr_image': exc.messages})

    qr_field = UserPayoutMethod._meta.get_field('qr_image')
    stored_name = None
    try:
        with transaction.atomic():
            _lock_user(user)
            if _has_active_upi(user, upi_id):
                raise DuplicatePayoutMethod()

            previous_default = _current_default(user)
            becomes_default = previous_default is None or make_default
            if previous_default is not None and make_default:
                _save_flags(previous_default, is_default=False)

            stored_name = qr_field.storage.save(
                qr_field.generate_filename(None, qr.content.name), qr.content
            )
            method = UserPayoutMethod(
                user=user,
                provider=provider,
                label=label,
                upi_id=upi_id,
                qr_image=stored_name,
                qr_sha256=qr.sha256,
                is_default=becomes_default,
            )
            try:
                method.full_clean()
            finally:
                # Validation re-opens the stored file; release the handle.
                method.qr_image.close()
            method.save()

            record_audit_event(
                actor=user,
                action=AuditEvent.Action.PAYOUT_METHOD_CREATED,
                target=method,
                ip_address=ip_address,
                metadata={'provider': provider.code, 'is_default': becomes_default},
            )
            if becomes_default and previous_default is not None:
                _record_default_changed(user, method, previous_default, ip_address)
            return method
    except ValidationError as exc:
        _discard(qr_field, stored_name)
        raise InvalidPayoutMethod(errors=exc.message_dict)
    except IntegrityError:
        _discard(qr_field, stored_name)
        raise PayoutMethodConflict()
    except BaseException:
        _discard(qr_field, stored_name)
        raise


def set_default_payout_method(*, user, method_id, ip_address=None):
    _require_member(user)
    try:
        with transaction.atomic():
            _lock_user(user)
            method = _get_own_method_for_update(user, method_id)
            if not method.is_active:
                raise PayoutMethodInactive()
            if method.is_default:
                return method

            previous_default = _current_default(user)
            if previous_default is not None:
                _save_flags(previous_default, is_default=False)
            _save_flags(method, is_default=True)

            _record_default_changed(user, method, previous_default, ip_address)
            return method
    except IntegrityError:
        raise PayoutMethodConflict()


def deactivate_payout_method(*, user, method_id, ip_address=None):
    _require_member(user)
    try:
        with transaction.atomic():
            _lock_user(user)
            method = _get_own_method_for_update(user, method_id)
            if not method.is_active:
                return method

            was_default = method.is_default
            # A member may end up with no active method; the claim service
            # rejects claims in that state. Only the flags change here - the
            # UPI ID, QR and any Order referencing this method are untouched.
            _save_flags(method, is_default=False, is_active=False)

            new_default = None
            if was_default:
                new_default = (
                    UserPayoutMethod.objects.select_for_update()
                    .filter(user=user, is_active=True)
                    .order_by(*REPLACEMENT_ORDER)
                    .first()
                )
                if new_default is not None:
                    _save_flags(new_default, is_default=True)

            record_audit_event(
                actor=user,
                action=AuditEvent.Action.PAYOUT_METHOD_DEACTIVATED,
                target=method,
                ip_address=ip_address,
                metadata={
                    'was_default': was_default,
                    'new_default_id': new_default.pk if new_default else None,
                },
            )
            if new_default is not None:
                _record_default_changed(user, new_default, method, ip_address)
            return method
    except IntegrityError:
        raise PayoutMethodConflict()


def _require_member(user):
    if not is_verified_member(user):
        raise PayoutMethodPermissionDenied()


def _get_available_provider(provider_id):
    try:
        provider = PaymentProvider.objects.filter(
            pk=provider_id,
            is_active=True,
            enabled_for_payout_methods=True,
        ).first()
    except (TypeError, ValueError):
        provider = None
    if provider is None:
        raise InvalidPayoutMethod(errors={'provider': ['Choose an available payout provider.']})
    return provider


def _lock_user(user):
    # Serializes all payout-method changes for this member (a row lock on
    # PostgreSQL; SQLite serializes writers at the database level instead).
    User.objects.select_for_update().only('pk').get(pk=user.pk)


def _get_own_method_for_update(user, method_id):
    try:
        return UserPayoutMethod.objects.select_for_update().get(pk=method_id, user=user)
    except (UserPayoutMethod.DoesNotExist, TypeError, ValueError):
        raise PayoutMethodNotFound()


def _has_active_upi(user, upi_id):
    return UserPayoutMethod.objects.filter(
        user=user, is_active=True, upi_id__iexact=upi_id
    ).exists()


def _current_default(user):
    return (
        UserPayoutMethod.objects.select_for_update()
        .filter(user=user, is_default=True)
        .first()
    )


def _save_flags(method, **flags):
    # Instance save (never QuerySet.update) so the immutability guard runs.
    for field, value in flags.items():
        setattr(method, field, value)
    method.save(update_fields=[*flags, 'updated_at'])


def _record_default_changed(user, method, previous_default, ip_address):
    record_audit_event(
        actor=user,
        action=AuditEvent.Action.PAYOUT_METHOD_DEFAULT_CHANGED,
        target=method,
        ip_address=ip_address,
        metadata={'previous_default_id': previous_default.pk if previous_default else None},
    )


def _discard(qr_field, stored_name):
    if stored_name:
        qr_field.storage.delete(stored_name)
