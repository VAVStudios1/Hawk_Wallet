import hashlib
import json
import threading
from io import BytesIO

import pytest
from django.contrib.auth.models import AnonymousUser
from django.core.files.uploadedfile import SimpleUploadedFile
from django.db import IntegrityError, connection, transaction
from PIL import Image
from PIL.PngImagePlugin import PngInfo

from audit.models import AuditEvent
from paymethods import services
from paymethods.exceptions import (
    DuplicatePayoutMethod,
    InvalidPayoutMethod,
    PayoutMethodConflict,
    PayoutMethodInactive,
    PayoutMethodNotFound,
    PayoutMethodPermissionDenied,
)
from paymethods.models import PaymentProvider, UserPayoutMethod
from paymethods.services import (
    add_payout_method,
    deactivate_payout_method,
    set_default_payout_method,
)
from paymethods.storage import private_storage


@pytest.fixture(autouse=True)
def private_media_root(settings, tmp_path):
    # Keep uploads out of the real PRIVATE_MEDIA_ROOT; the storage caches its
    # location, so drop the cache on the way in and out.
    settings.PRIVATE_MEDIA_ROOT = tmp_path
    for attr in ('base_location', 'location'):
        private_storage.__dict__.pop(attr, None)
    yield tmp_path
    for attr in ('base_location', 'location'):
        private_storage.__dict__.pop(attr, None)


def _upload(fmt='PNG', name='qr.png', size=(64, 64), **save_kwargs):
    buffer = BytesIO()
    Image.new('RGB', size, 'white').save(buffer, format=fmt, **save_kwargs)
    return SimpleUploadedFile(name, buffer.getvalue())


def _stored_files(root):
    folder = root / 'payout_qr'
    return sorted(folder.iterdir()) if folder.exists() else []


def _events(method, action=None):
    events = AuditEvent.objects.filter(
        target_type='paymethods.UserPayoutMethod', target_id=str(method.pk)
    )
    return events.filter(action=action) if action else events


def _defaults(user):
    return list(UserPayoutMethod.objects.filter(user=user, is_default=True))


@pytest.fixture
def add(provider):
    def _add(user, upi_id='user@ybl', **kwargs):
        kwargs.setdefault('qr_image', _upload())
        return add_payout_method(user=user, provider_id=provider.pk, upi_id=upi_id, **kwargs)
    return _add


# --- add_payout_method -------------------------------------------------------

def test_add_success(user, provider, add, private_media_root):
    method = add(user, 'rahul@ybl', label='My PhonePe', ip_address='10.0.0.1')

    method.refresh_from_db()
    assert method.user == user
    assert method.provider == provider
    assert method.upi_id == 'rahul@ybl'
    assert method.label == 'My PhonePe'
    assert method.is_active
    assert method.qr_image.name.startswith('payout_qr/')
    assert method.qr_image.name.endswith('.png')
    assert (private_media_root / method.qr_image.name).exists()

    event = _events(method, AuditEvent.Action.PAYOUT_METHOD_CREATED).get()
    assert event.actor == user
    assert event.ip_address == '10.0.0.1'
    assert event.metadata == {'provider': 'PHONEPE', 'is_default': True}
    assert 'rahul@ybl' not in json.dumps(event.metadata)


def test_first_method_becomes_default(user, add):
    method = add(user)
    assert method.is_default
    assert _defaults(user) == [method]


def test_second_method_keeps_existing_default(user, add):
    first = add(user, 'first@ybl')
    second = add(user, 'second@ybl')
    assert not second.is_default
    assert _defaults(user) == [first]
    assert not _events(second, AuditEvent.Action.PAYOUT_METHOD_DEFAULT_CHANGED).exists()


def test_add_with_make_default_moves_default(user, add):
    first = add(user, 'first@ybl')
    second = add(user, 'second@ybl', make_default=True)
    assert _defaults(user) == [second]
    first.refresh_from_db()
    assert not first.is_default and first.is_active
    event = _events(second, AuditEvent.Action.PAYOUT_METHOD_DEFAULT_CHANGED).get()
    assert event.metadata == {'previous_default_id': first.pk}


def test_duplicate_active_upi_rejected(user, add, private_media_root):
    add(user, 'rahul@ybl')
    with pytest.raises(DuplicatePayoutMethod):
        add(user, 'RAHUL@ybl')
    assert UserPayoutMethod.objects.filter(user=user).count() == 1
    assert len(_stored_files(private_media_root)) == 1


def test_upi_can_be_reused_after_deactivation(user, add):
    old = add(user, 'rahul@ybl')
    add(user, 'other@ybl')
    deactivate_payout_method(user=user, method_id=old.pk)
    add(user, 'rahul@ybl')


def test_other_users_may_use_same_upi(make_user, add):
    add(make_user(), 'shared@ybl')
    add(make_user(), 'shared@ybl')


@pytest.mark.parametrize(
    'provider_values',
    [
        dict(is_active=False, enabled_for_payout_methods=True),
        dict(is_active=True, enabled_for_payout_methods=False),
    ],
)
def test_unavailable_provider_rejected(user, provider_values):
    provider = PaymentProvider.objects.create(code='PAYTM', display_name='Paytm', **provider_values)
    with pytest.raises(InvalidPayoutMethod) as exc:
        add_payout_method(user=user, provider_id=provider.pk, upi_id='u@paytm', qr_image=_upload())
    assert 'provider' in exc.value.errors


@pytest.mark.parametrize('provider_id', [999999, 'abc', None])
def test_unknown_provider_rejected(user, provider_id):
    with pytest.raises(InvalidPayoutMethod) as exc:
        add_payout_method(user=user, provider_id=provider_id, upi_id='u@ybl', qr_image=_upload())
    assert 'provider' in exc.value.errors


@pytest.mark.parametrize('upi_id', ['', 'no-at-sign', 'a@', '@ybl', 'user name@ybl', 'user@y b l'])
def test_invalid_upi_rejected(user, add, upi_id):
    with pytest.raises(InvalidPayoutMethod) as exc:
        add(user, upi_id)
    assert 'upi_id' in exc.value.errors
    assert not UserPayoutMethod.objects.exists()


@pytest.mark.parametrize(
    'upload',
    [
        SimpleUploadedFile('qr.png', b'not an image'),
        _upload(fmt='GIF', name='qr.gif'),
        _upload(fmt='GIF', name='qr.png'),          # right extension, wrong content
        _upload(name='qr.png', size=(5000, 10)),   # too large
        _upload(fmt='PNG', name='qr.exe'),
    ],
)
def test_invalid_qr_rejected(user, add, private_media_root, upload):
    with pytest.raises(InvalidPayoutMethod) as exc:
        add(user, qr_image=upload)
    assert 'qr_image' in exc.value.errors
    assert not UserPayoutMethod.objects.exists()
    assert _stored_files(private_media_root) == []


def test_exif_removed_from_stored_qr(user, add):
    exif = Image.Exif()
    exif[0x010F] = 'SecretCameraMaker'  # Make
    exif[0x0110] = 'SecretModel'        # Model
    upload = _upload(fmt='JPEG', name='qr.jpg', exif=exif)
    assert b'SecretCameraMaker' in upload.read()  # the input really carries EXIF
    upload.seek(0)
    method = add(user, qr_image=upload)

    with private_storage.open(method.qr_image.name) as stored_file:
        data = stored_file.read()
    stored = Image.open(BytesIO(data))
    assert stored.format == 'PNG'
    assert len(stored.getexif()) == 0
    assert 'exif' not in stored.info
    assert b'SecretCameraMaker' not in data


def test_png_text_metadata_removed(user, add):
    info = PngInfo()
    info.add_text('Author', 'secret-author-name')
    method = add(user, qr_image=_upload(pnginfo=info))
    with private_storage.open(method.qr_image.name) as stored_file:
        assert b'secret-author-name' not in stored_file.read()


def test_exif_orientation_applied_before_stripping(user, add):
    exif = Image.Exif()
    exif[0x0112] = 6  # rotate 90 degrees clockwise to display
    method = add(user, qr_image=_upload(fmt='JPEG', name='qr.jpg', size=(40, 20), exif=exif))
    with private_storage.open(method.qr_image.name) as stored_file:
        assert Image.open(stored_file).size == (20, 40)


def test_sha256_matches_stored_sanitized_image(user, add):
    upload = _upload(fmt='JPEG', name='qr.jpg', exif=Image.Exif())
    original_hash = hashlib.sha256(upload.read()).hexdigest()
    upload.seek(0)

    method = add(user, qr_image=upload)
    with private_storage.open(method.qr_image.name) as stored_file:
        stored_hash = hashlib.sha256(stored_file.read()).hexdigest()
    assert method.qr_sha256 == stored_hash
    assert method.qr_sha256 != original_hash


def test_stored_qr_has_no_public_url(user, add):
    method = add(user)
    with pytest.raises(NotImplementedError):
        method.qr_image.url


@pytest.mark.parametrize(
    'make_actor',
    [
        lambda make_user: AnonymousUser(),
        lambda make_user: make_user(role='ADMIN'),
        lambda make_user: make_user(role='SUPER_ADMIN'),
        lambda make_user: make_user(is_verified=False),
        lambda make_user: make_user(is_active=False),
    ],
)
def test_only_verified_active_members_may_add(make_user, add, make_actor):
    with pytest.raises(PayoutMethodPermissionDenied):
        add(make_actor(make_user))
    assert not UserPayoutMethod.objects.exists()


# --- set_default_payout_method ----------------------------------------------

def test_set_default_success(user, add):
    first = add(user, 'first@ybl')
    second = add(user, 'second@ybl')

    set_default_payout_method(user=user, method_id=second.pk, ip_address='10.0.0.2')

    assert _defaults(user) == [second]
    event = _events(second, AuditEvent.Action.PAYOUT_METHOD_DEFAULT_CHANGED).get()
    assert event.metadata == {'previous_default_id': first.pk}
    assert event.ip_address == '10.0.0.2'


def test_set_default_is_idempotent(user, add):
    method = add(user)
    set_default_payout_method(user=user, method_id=method.pk)
    assert _defaults(user) == [method]
    assert not _events(method, AuditEvent.Action.PAYOUT_METHOD_DEFAULT_CHANGED).exists()


def test_cannot_set_other_users_method_as_default(make_user, add):
    owner, intruder = make_user(), make_user()
    target = add(owner, 'owner@ybl')
    add(owner, 'owner2@ybl')
    own = add(intruder, 'intruder@ybl')

    with pytest.raises(PayoutMethodNotFound):
        set_default_payout_method(user=intruder, method_id=target.pk)
    assert _defaults(intruder) == [own]
    assert _defaults(owner) == [target]


def test_cannot_set_inactive_method_as_default(user, add):
    old = add(user, 'old@ybl')
    add(user, 'new@ybl')
    deactivate_payout_method(user=user, method_id=old.pk)
    with pytest.raises(PayoutMethodInactive):
        set_default_payout_method(user=user, method_id=old.pk)


def test_set_default_requires_member(make_user, add, user):
    method = add(user)
    with pytest.raises(PayoutMethodPermissionDenied):
        set_default_payout_method(user=make_user(role='ADMIN'), method_id=method.pk)


@pytest.mark.parametrize('action', ['set_default', 'deactivate'])
@pytest.mark.parametrize(
    'make_actor',
    [
        lambda make_user, owner: AnonymousUser(),
        lambda make_user, owner: make_user(role='ADMIN'),
        lambda make_user, owner: make_user(role='SUPER_ADMIN'),
        lambda make_user, owner: make_user(is_verified=False),
        lambda make_user, owner: make_user(is_active=False),
        # The owner themself, once no longer an active verified member.
        lambda make_user, owner: _with_flags(owner, is_active=False),
        lambda make_user, owner: _with_flags(owner, is_verified=False),
    ],
)
def test_only_verified_active_members_may_change_methods(make_user, add, user, action, make_actor):
    first = add(user, 'first@ybl')
    second = add(user, 'second@ybl')
    actor = make_actor(make_user, user)
    call = set_default_payout_method if action == 'set_default' else deactivate_payout_method
    target = second if action == 'set_default' else first

    with pytest.raises(PayoutMethodPermissionDenied):
        call(user=actor, method_id=target.pk)

    first.refresh_from_db()
    second.refresh_from_db()
    assert first.is_active and first.is_default
    assert second.is_active and not second.is_default


def _with_flags(user, **flags):
    for name, value in flags.items():
        setattr(user, name, value)
    user.save(update_fields=list(flags))
    return user


# --- deactivate_payout_method -----------------------------------------------

def test_cannot_deactivate_other_users_method(make_user, add):
    owner, intruder = make_user(), make_user()
    target = add(owner, 'owner@ybl')
    add(owner, 'owner2@ybl')
    with pytest.raises(PayoutMethodNotFound):
        deactivate_payout_method(user=intruder, method_id=target.pk)
    target.refresh_from_db()
    assert target.is_active and target.is_default


def test_can_deactivate_last_active_method(user, add):
    only = add(user)
    deactivate_payout_method(user=user, method_id=only.pk)

    only.refresh_from_db()
    assert not only.is_active and not only.is_default
    assert not UserPayoutMethod.objects.filter(user=user, is_active=True).exists()
    assert _defaults(user) == []
    event = _events(only, AuditEvent.Action.PAYOUT_METHOD_DEACTIVATED).get()
    assert event.metadata == {'was_default': True, 'new_default_id': None}
    assert not AuditEvent.objects.filter(action=AuditEvent.Action.PAYOUT_METHOD_DEFAULT_CHANGED).exists()


def test_method_added_after_deactivating_all_becomes_default(user, add):
    old = add(user, 'old@ybl')
    deactivate_payout_method(user=user, method_id=old.pk)

    new = add(user, 'new@ybl')
    assert new.is_default
    assert _defaults(user) == [new]
    # No stale default to replace, so no default-change event.
    assert not _events(new, AuditEvent.Action.PAYOUT_METHOD_DEFAULT_CHANGED).exists()


def test_same_upi_can_be_readded_after_deactivating_only_method(user, add):
    old = add(user, 'rahul@ybl')
    deactivate_payout_method(user=user, method_id=old.pk)
    readded = add(user, 'rahul@ybl')
    assert readded.pk != old.pk and readded.is_default


def test_deactivating_only_method_leaves_existing_order_untouched(user, add, make_order):
    method = add(user, 'original@ybl')
    order = make_order(user, payout_method=method)

    deactivate_payout_method(user=user, method_id=method.pk)

    order.refresh_from_db()
    method.refresh_from_db()
    assert order.payout_method_id == method.pk
    assert method.upi_id == 'original@ybl'
    assert method.qr_image.name.startswith('payout_qr/')


def test_deactivating_default_picks_newest_active_replacement(user, add):
    default = add(user, 'first@ybl')
    older = add(user, 'second@ybl')
    newest = add(user, 'third@ybl')

    deactivate_payout_method(user=user, method_id=default.pk)

    default.refresh_from_db()
    assert not default.is_active and not default.is_default
    assert _defaults(user) == [newest]
    older.refresh_from_db()
    assert older.is_active and not older.is_default

    event = _events(default, AuditEvent.Action.PAYOUT_METHOD_DEACTIVATED).get()
    assert event.metadata == {'was_default': True, 'new_default_id': newest.pk}
    changed = _events(newest, AuditEvent.Action.PAYOUT_METHOD_DEFAULT_CHANGED).get()
    assert changed.metadata == {'previous_default_id': default.pk}


def test_deactivating_non_default_keeps_default(user, add):
    default = add(user, 'first@ybl')
    other = add(user, 'second@ybl')
    deactivate_payout_method(user=user, method_id=other.pk)
    assert _defaults(user) == [default]
    event = _events(other, AuditEvent.Action.PAYOUT_METHOD_DEACTIVATED).get()
    assert event.metadata == {'was_default': False, 'new_default_id': None}


def test_deactivate_is_idempotent(user, add):
    old = add(user, 'first@ybl')
    add(user, 'second@ybl')
    deactivate_payout_method(user=user, method_id=old.pk)
    deactivate_payout_method(user=user, method_id=old.pk)
    assert _events(old, AuditEvent.Action.PAYOUT_METHOD_DEACTIVATED).count() == 1


def test_exactly_one_default_through_every_operation(user, add):
    methods = [add(user, f'u{i}@ybl') for i in range(3)]
    assert len(_defaults(user)) == 1
    set_default_payout_method(user=user, method_id=methods[2].pk)
    assert len(_defaults(user)) == 1
    methods.append(add(user, 'u3@ybl', make_default=True))
    assert len(_defaults(user)) == 1
    deactivate_payout_method(user=user, method_id=methods[3].pk)
    assert len(_defaults(user)) == 1
    deactivate_payout_method(user=user, method_id=methods[0].pk)
    assert len(_defaults(user)) == 1
    assert all(m.is_active for m in _defaults(user))


def test_existing_order_keeps_its_payout_method(user, add, make_order):
    original = add(user, 'original@ybl')
    order = make_order(user, payout_method=original)
    replacement = add(user, 'new@ybl')

    set_default_payout_method(user=user, method_id=replacement.pk)
    deactivate_payout_method(user=user, method_id=original.pk)

    order.refresh_from_db()
    original.refresh_from_db()
    assert order.payout_method_id == original.pk
    assert original.upi_id == 'original@ybl'
    assert original.qr_image.name.startswith('payout_qr/')
    assert not original.is_active


# --- concurrency backstops (single connection) -------------------------------

def test_race_on_duplicate_upi_hits_db_constraint(user, add, monkeypatch, private_media_root):
    add(user, 'rahul@ybl')
    # Simulate a concurrent insert the application check could not see yet.
    monkeypatch.setattr(services, '_has_active_upi', lambda user, upi_id: False)
    with pytest.raises((PayoutMethodConflict, InvalidPayoutMethod)):
        add(user, 'rahul@ybl')
    assert UserPayoutMethod.objects.filter(user=user).count() == 1
    assert len(_stored_files(private_media_root)) == 1
    assert AuditEvent.objects.filter(action=AuditEvent.Action.PAYOUT_METHOD_CREATED).count() == 1


def test_race_on_default_hits_db_constraint(user, add, monkeypatch):
    first = add(user, 'first@ybl')
    second = add(user, 'second@ybl')
    # Simulate a stale read that misses the current default.
    monkeypatch.setattr(services, '_current_default', lambda user: None)
    with pytest.raises(PayoutMethodConflict):
        set_default_payout_method(user=user, method_id=second.pk)
    assert _defaults(user) == [first]
    assert not _events(second, AuditEvent.Action.PAYOUT_METHOD_DEFAULT_CHANGED).exists()


def test_db_rejects_second_default_even_without_services(user, add):
    add(user, 'first@ybl')
    second = add(user, 'second@ybl')
    second.is_default = True
    with pytest.raises(IntegrityError), transaction.atomic():
        second.save()


# --- real concurrency (PostgreSQL only) --------------------------------------

@pytest.mark.skipif(
    connection.vendor != 'postgresql',
    reason='Row locks (select_for_update) are a no-op on SQLite.',
)
@pytest.mark.django_db(transaction=True)
def test_concurrent_set_default_leaves_one_default(make_user, provider):
    user = make_user()
    methods = [
        add_payout_method(user=user, provider_id=provider.pk, upi_id=f'c{i}@ybl', qr_image=_upload())
        for i in range(4)
    ]
    barrier = threading.Barrier(len(methods))
    errors = []

    def worker(method):
        try:
            barrier.wait()
            set_default_payout_method(user=user, method_id=method.pk)
        except Exception as exc:  # pragma: no cover - reported below
            errors.append(exc)
        finally:
            connection.close()

    threads = [threading.Thread(target=worker, args=(m,)) for m in methods]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()

    assert errors == []
    assert len(_defaults(user)) == 1
