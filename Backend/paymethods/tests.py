from io import BytesIO

import pytest
from django.core.exceptions import ValidationError
from django.core.files.uploadedfile import SimpleUploadedFile
from django.db import IntegrityError, transaction
from django.db.models import ProtectedError
from PIL import Image

from audit.mixins import ImmutableFieldError
from paymethods.storage import private_storage
from paymethods.validators import payout_qr_upload_to, validate_qr_image


def _png(size=(64, 64)):
    buffer = BytesIO()
    Image.new('RGB', size, 'white').save(buffer, format='PNG')
    return SimpleUploadedFile('qr.png', buffer.getvalue(), content_type='image/png')


def test_only_one_default_method_per_user(user, make_method):
    make_method(user, 'a@ybl', is_default=True)
    with pytest.raises(IntegrityError), transaction.atomic():
        make_method(user, 'b@ybl', is_default=True)


def test_default_method_must_be_active(user, make_method):
    with pytest.raises(IntegrityError), transaction.atomic():
        make_method(user, 'a@ybl', is_default=True, is_active=False)


def test_duplicate_active_upi_rejected_case_insensitively(user, make_method):
    make_method(user, 'user@ybl')
    with pytest.raises(IntegrityError), transaction.atomic():
        make_method(user, 'USER@ybl')


def test_same_upi_allowed_after_old_method_deactivated(user, make_method):
    old = make_method(user, 'user@ybl')
    old.is_active = False
    old.save()
    make_method(user, 'user@ybl')


def test_same_upi_allowed_for_different_users(make_user, make_method):
    make_method(make_user(), 'shared@ybl')
    make_method(make_user(), 'shared@ybl')


def test_unused_method_destination_can_be_edited(user, make_method):
    method = make_method(user)
    method.upi_id = 'new@ybl'
    method.save()


def test_used_method_destination_is_frozen(user, make_method, make_order):
    method = make_method(user)
    make_order(user, payout_method=method)

    method.upi_id = 'changed@ybl'
    with pytest.raises(ImmutableFieldError):
        method.save()

    method.refresh_from_db()
    method.qr_image = 'payout_qr/other.png'
    with pytest.raises(ImmutableFieldError):
        method.save()


def test_used_method_can_still_be_deactivated(user, make_method, make_order):
    method = make_method(user, is_default=True)
    make_order(user, payout_method=method)
    method.is_default = False
    method.is_active = False
    method.save()


def test_used_method_and_provider_cannot_be_deleted(user, provider, make_method, make_order):
    method = make_method(user)
    make_order(user, payout_method=method)
    with pytest.raises(ProtectedError):
        method.delete()
    with pytest.raises(ProtectedError):
        provider.delete()


def test_qr_validator_accepts_png():
    validate_qr_image(_png())


def test_qr_validator_rejects_non_image():
    fake = SimpleUploadedFile('qr.png', b'not an image', content_type='image/png')
    with pytest.raises(ValidationError):
        validate_qr_image(fake)


def test_qr_validator_rejects_oversized_dimensions():
    with pytest.raises(ValidationError):
        validate_qr_image(_png(size=(5000, 10)))


def test_qr_upload_name_is_randomized():
    name = payout_qr_upload_to(None, 'my-upi-user@ybl.PNG')
    assert name.startswith('payout_qr/')
    assert 'user' not in name
    assert name.endswith('.png')
    assert payout_qr_upload_to(None, 'x.exe').startswith('payout_qr/')
    assert not payout_qr_upload_to(None, 'x.exe').endswith('.exe')


def test_private_storage_has_no_public_url():
    with pytest.raises(NotImplementedError):
        private_storage.url('payout_qr/test.png')
