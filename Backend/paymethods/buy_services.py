"""
Member-facing PERSONAL / BUY method services.

The member's own UPI accounts they pay for orders from. Kept apart from
payout (SELL) methods: separate model, provider flag, errors and audit
actions, so a BUY method can never become a payout destination and a payout
method can never be recorded as a BUY method. Same locking and default
rules as payout methods (see paymethods.services); there is no QR.
"""
from django.core.exceptions import ValidationError
from django.db import IntegrityError, transaction

from audit.models import AuditEvent
from audit.services import record_audit_event
from core.permissions import is_verified_member
from paymethods.exceptions import (
    BuyMethodConflict,
    BuyMethodInactive,
    BuyMethodNotFound,
    BuyMethodPermissionDenied,
    DuplicateBuyMethod,
    InvalidBuyMethod,
)
from paymethods.models import PaymentProvider, UserBuyMethod
from paymethods.services import REPLACEMENT_ORDER, _lock_user, _save_flags
from paymethods.validators import upi_id_validator


def resolve_buy_provider_id(*, provider_id=None, provider_code=None):
    """
    Return the PaymentProvider id to pass to add_buy_method. Mirrors
    resolve_payout_provider_id, but provider_code must name an active
    provider enabled for BUY methods.
    """
    if provider_code is None:
        return provider_id
    code = provider_code.strip().upper()
    provider = PaymentProvider.objects.filter(
        code=code,
        is_active=True,
        enabled_for_buy_methods=True,
    ).first()
    if provider is None:
        raise InvalidBuyMethod(errors={'provider': ['Choose an available UPI app.']})
    if provider_id is not None and provider_id != provider.pk:
        raise InvalidBuyMethod(
            errors={'provider_code': ['provider and provider_code refer to different providers.']}
        )
    return provider.pk


def list_buy_methods(*, user):
    """
    Return the member's own buy methods, active and inactive: the default
    first, then newest first (id breaks ties). Read-only.
    """
    _require_member(user)
    return (
        UserBuyMethod.objects.filter(user=user)
        .select_related('provider')
        .order_by('-is_default', '-created_at', '-id')
    )


def add_buy_method(*, user, provider_id, upi_id, label='', make_default=False, ip_address=None):
    _require_member(user)
    provider = _get_available_provider(provider_id)
    try:
        upi_id_validator(upi_id)
    except ValidationError as exc:
        raise InvalidBuyMethod(errors={'upi_id': exc.messages})

    try:
        with transaction.atomic():
            _lock_user(user)
            if UserBuyMethod.objects.filter(
                user=user, is_active=True, upi_id__iexact=upi_id
            ).exists():
                raise DuplicateBuyMethod()

            previous_default = _current_default(user)
            becomes_default = previous_default is None or make_default
            if previous_default is not None and make_default:
                _save_flags(previous_default, is_default=False)

            method = UserBuyMethod(
                user=user,
                provider=provider,
                label=label,
                upi_id=upi_id,
                is_default=becomes_default,
            )
            method.full_clean()
            method.save()

            record_audit_event(
                actor=user,
                action=AuditEvent.Action.BUY_METHOD_CREATED,
                target=method,
                ip_address=ip_address,
                metadata={'provider': provider.code, 'is_default': becomes_default},
            )
            if becomes_default and previous_default is not None:
                _record_default_changed(user, method, previous_default, ip_address)
            return method
    except ValidationError as exc:
        raise InvalidBuyMethod(errors=exc.message_dict)
    except IntegrityError:
        raise BuyMethodConflict()


def set_default_buy_method(*, user, method_id, ip_address=None):
    _require_member(user)
    try:
        with transaction.atomic():
            _lock_user(user)
            method = _get_own_method_for_update(user, method_id)
            if not method.is_active:
                raise BuyMethodInactive()
            if method.is_default:
                return method

            previous_default = _current_default(user)
            if previous_default is not None:
                _save_flags(previous_default, is_default=False)
            _save_flags(method, is_default=True)

            _record_default_changed(user, method, previous_default, ip_address)
            return method
    except IntegrityError:
        raise BuyMethodConflict()


def deactivate_buy_method(*, user, method_id, ip_address=None):
    _require_member(user)
    try:
        with transaction.atomic():
            _lock_user(user)
            method = _get_own_method_for_update(user, method_id)
            if not method.is_active:
                return method

            was_default = method.is_default
            # A member may end up with no active buy method; Pay-In submission
            # rejects that state. PayIns referencing this method are untouched.
            _save_flags(method, is_default=False, is_active=False)

            new_default = None
            if was_default:
                new_default = (
                    UserBuyMethod.objects.select_for_update()
                    .filter(user=user, is_active=True)
                    .order_by(*REPLACEMENT_ORDER)
                    .first()
                )
                if new_default is not None:
                    _save_flags(new_default, is_default=True)

            record_audit_event(
                actor=user,
                action=AuditEvent.Action.BUY_METHOD_DEACTIVATED,
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
        raise BuyMethodConflict()


def _require_member(user):
    if not is_verified_member(user):
        raise BuyMethodPermissionDenied()


def _get_available_provider(provider_id):
    try:
        provider = PaymentProvider.objects.filter(
            pk=provider_id,
            is_active=True,
            enabled_for_buy_methods=True,
        ).first()
    except (TypeError, ValueError):
        provider = None
    if provider is None:
        raise InvalidBuyMethod(errors={'provider': ['Choose an available UPI app.']})
    return provider


def _get_own_method_for_update(user, method_id):
    try:
        return UserBuyMethod.objects.select_for_update().get(pk=method_id, user=user)
    except (UserBuyMethod.DoesNotExist, TypeError, ValueError):
        raise BuyMethodNotFound()


def _current_default(user):
    return (
        UserBuyMethod.objects.select_for_update()
        .filter(user=user, is_default=True)
        .first()
    )


def _record_default_changed(user, method, previous_default, ip_address):
    record_audit_event(
        actor=user,
        action=AuditEvent.Action.BUY_METHOD_DEFAULT_CHANGED,
        target=method,
        ip_address=ip_address,
        metadata={'previous_default_id': previous_default.pk if previous_default else None},
    )
