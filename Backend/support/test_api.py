from io import BytesIO

import pytest
from django.core.cache import cache
from django.core.files.uploadedfile import SimpleUploadedFile
from PIL import Image
from rest_framework.test import APIClient

from audit.models import AuditEvent
from core.models import User
from paymethods.storage import private_storage
from support import services
from support.attachments import MAX_ATTACHMENT_BYTES
from support.models import Conversation, Message

MEMBER_BASE = '/api/support/'
ADMIN_BASE = '/api/admin/support/'
PDF_BYTES = b'%PDF-1.4\n1 0 obj << /Type /Catalog >> endobj\ntrailer << /Root 1 0 R >>\n%%EOF\n'


@pytest.fixture(autouse=True)
def private_media_root(settings, tmp_path):
    settings.PRIVATE_MEDIA_ROOT = tmp_path
    for attr in ('base_location', 'location'):
        private_storage.__dict__.pop(attr, None)
    yield tmp_path
    for attr in ('base_location', 'location'):
        private_storage.__dict__.pop(attr, None)


@pytest.fixture(autouse=True)
def reset_throttles():
    # Upload throttle counters live in the cache and would carry across tests.
    cache.clear()
    yield
    cache.clear()


def _client(user=None):
    client = APIClient()
    if user is not None:
        client.force_authenticate(user)
    return client


def _png(name='photo.png', size=(32, 32), mode='RGB', exif=False):
    buffer = BytesIO()
    image = Image.new(mode, size, 'white')
    if exif:
        data = Image.Exif()
        data[0x010F] = 'CameraMaker'
        image.save(buffer, format='JPEG', exif=data)
        return SimpleUploadedFile(name, buffer.getvalue(), content_type='image/jpeg')
    image.save(buffer, format='PNG')
    return SimpleUploadedFile(name, buffer.getvalue(), content_type='image/png')


def _pdf(name='statement.pdf', data=PDF_BYTES):
    return SimpleUploadedFile(name, data, content_type='application/pdf')


@pytest.fixture
def member(make_user):
    return make_user()


@pytest.fixture
def other_member(make_user):
    return make_user()


@pytest.fixture
def super_admin(make_user):
    return make_user(role=User.Role.SUPER_ADMIN)


def _start(user):
    response = _client(user).post(f'{MEMBER_BASE}conversation/')
    assert response.status_code in (200, 201), response.data
    return response


def _conversation(user):
    return Conversation.objects.get(user=user, status=Conversation.Status.OPEN)


# --- start / duplicate ------------------------------------------------------

def test_member_can_start_conversation(member, admin_user):
    response = _start(member)
    assert response.status_code == 201
    body = response.data['data']['conversation']
    assert body['status'] == 'OPEN'
    assert body['unread_count'] == 0
    # No assignment or routing information for the member.
    assert 'assigned_admin' not in body and 'admin' not in str(body).lower()
    assert _conversation(member).assigned_admin == admin_user


def test_starting_twice_returns_same_open_conversation(member, admin_user):
    first = _start(member).data['data']['conversation']['id']
    second = _start(member)
    assert second.status_code == 200
    assert second.data['data']['conversation']['id'] == first
    assert Conversation.objects.filter(user=member).count() == 1


def test_start_audits_routing_once(member, admin_user):
    _start(member)
    _start(member)
    events = AuditEvent.objects.filter(action=AuditEvent.Action.SUPPORT_CONVERSATION_STARTED)
    assert events.count() == 1
    assert events.get().metadata['routing'] == services.ROUTING_LEAST_LOADED


def test_start_without_any_admin_is_unavailable(member):
    response = _client(member).post(f'{MEMBER_BASE}conversation/')
    assert response.status_code == 503
    assert response.data['errors']['code'] == 'support_unavailable'


def test_admins_cannot_start_member_conversation(admin_user):
    assert _client(admin_user).post(f'{MEMBER_BASE}conversation/').status_code == 403


def test_get_conversation_is_null_before_start(member):
    response = _client(member).get(f'{MEMBER_BASE}conversation/')
    assert response.status_code == 200
    assert response.data['data']['conversation'] is None


# --- routing ----------------------------------------------------------------

def test_routing_picks_least_loaded_active_admin(make_user):
    busy = make_user(role=User.Role.ADMIN)
    idle = make_user(role=User.Role.ADMIN)
    make_user(role=User.Role.ADMIN, is_active=False)
    make_user(role=User.Role.SUPER_ADMIN)
    _start(make_user())  # tie -> lowest id (busy)
    assert Conversation.objects.get().assigned_admin == busy
    newcomer = make_user()
    _start(newcomer)
    assert _conversation(newcomer).assigned_admin == idle


def test_assignment_persists_after_close(member, make_user):
    first_admin = make_user(role=User.Role.ADMIN)
    _start(member)
    make_user(role=User.Role.ADMIN)  # now less loaded than first_admin
    conversation = _conversation(member)
    services.close_conversation(admin=first_admin, conversation_id=conversation.public_id)
    _start(member)
    assert _conversation(member).assigned_admin == first_admin
    event = AuditEvent.objects.filter(action=AuditEvent.Action.SUPPORT_CONVERSATION_STARTED).last()
    assert event.metadata['routing'] == services.ROUTING_PREVIOUS_ADMIN


def test_assignment_is_immutable(member, admin_user, make_user):
    _start(member)
    conversation = _conversation(member)
    conversation.assigned_admin = make_user(role=User.Role.ADMIN)
    with pytest.raises(Exception):
        conversation.save()


# --- access control ----------------------------------------------------------

def test_member_only_sees_own_messages(member, other_member, admin_user):
    _start(member)
    _client(member).post(f'{MEMBER_BASE}conversation/messages/', {'text': 'mine'}, format='json')
    _start(other_member)
    response = _client(other_member).get(f'{MEMBER_BASE}conversation/messages/')
    assert response.data['data']['messages'] == []


def test_member_cannot_download_another_members_attachment(member, other_member, admin_user):
    _start(member)
    sent = _client(member).post(
        f'{MEMBER_BASE}conversation/attachments/', {'type': 'IMAGE', 'file': _png()}, format='multipart'
    )
    message_id = sent.data['data']['id']
    response = _client(other_member).get(f'{MEMBER_BASE}attachments/{message_id}/')
    assert response.status_code == 404


def test_unassigned_admin_cannot_access_conversation(member, admin_user, make_user):
    _start(member)
    stranger = make_user(role=User.Role.ADMIN)
    conversation_id = _conversation(member).public_id
    client = _client(stranger)
    assert client.get(f'{ADMIN_BASE}conversations/{conversation_id}/').status_code == 404
    assert client.get(f'{ADMIN_BASE}conversations/{conversation_id}/messages/').status_code == 404
    response = client.post(
        f'{ADMIN_BASE}conversations/{conversation_id}/messages/', {'text': 'hi'}, format='json'
    )
    assert response.status_code == 404
    assert client.get(f'{ADMIN_BASE}conversations/').data['data']['conversations'] == []


def test_assigned_admin_can_read_and_reply(member, admin_user):
    _start(member)
    conversation_id = _conversation(member).public_id
    _client(member).post(f'{MEMBER_BASE}conversation/messages/', {'text': 'help'}, format='json')
    client = _client(admin_user)
    listed = client.get(f'{ADMIN_BASE}conversations/').data['data']['conversations']
    assert listed[0]['id'] == str(conversation_id)
    assert listed[0]['unread_count'] == 1
    reply = client.post(
        f'{ADMIN_BASE}conversations/{conversation_id}/messages/', {'text': 'hello'}, format='json'
    )
    assert reply.status_code == 201
    member_view = _client(member).get(f'{MEMBER_BASE}conversation/messages/').data['data']['messages']
    assert member_view[-1]['from_support'] is True
    assert member_view[-1]['sender_name'] == 'Hawk Wallet Support'


def test_super_admin_views_all_read_only_and_is_audited(member, admin_user, super_admin):
    _start(member)
    conversation_id = _conversation(member).public_id
    client = _client(super_admin)
    listed = client.get(f'{ADMIN_BASE}conversations/').data['data']['conversations']
    assert listed[0]['is_assigned_to_me'] is False
    assert client.get(f'{ADMIN_BASE}conversations/{conversation_id}/').status_code == 200
    assert AuditEvent.objects.filter(
        action=AuditEvent.Action.SUPPORT_CONVERSATION_VIEWED, actor=super_admin
    ).count() == 1
    send = client.post(f'{ADMIN_BASE}conversations/{conversation_id}/messages/', {'text': 'x'}, format='json')
    assert send.status_code == 403
    assert send.data['errors']['code'] == 'conversation_read_only'
    assert client.post(f'{ADMIN_BASE}conversations/{conversation_id}/close/').status_code == 403


def test_super_admin_is_read_only_even_when_assigned(member, super_admin):
    # e.g. an ADMIN promoted after routing: the role, not the assignment, decides.
    conversation = Conversation.objects.create(user=member, assigned_admin=super_admin)
    message = services.send_member_text(user=member, text='hi')
    client = _client(super_admin)
    base = f'{ADMIN_BASE}conversations/{conversation.public_id}/'
    assert client.get(base).status_code == 200
    assert client.get(f'{base}messages/').status_code == 200
    for response in (
        client.post(f'{base}messages/', {'text': 'x'}, format='json'),
        client.post(f'{base}read/', {'up_to': str(message.public_id)}, format='json'),
        client.post(f'{base}close/'),
    ):
        assert response.status_code == 403
        assert response.data['errors']['code'] == 'conversation_read_only'
    conversation.refresh_from_db()
    assert conversation.status == Conversation.Status.OPEN
    assert conversation.admin_last_read_at is None
    assert Message.objects.filter(conversation=conversation).count() == 1


def test_message_sender_must_be_participant(member, admin_user, make_user):
    _start(member)
    conversation = _conversation(member)
    with pytest.raises(Exception):
        Message.objects.create(
            conversation=conversation, sender=make_user(), type=Message.Type.TEXT, text='x'
        )


def test_messages_are_append_only(member, admin_user):
    _start(member)
    message = services.send_member_text(user=member, text='hi')
    message.text = 'edited'
    with pytest.raises(Exception):
        message.save()
    with pytest.raises(Exception):
        message.delete()


# --- closed conversations ------------------------------------------------------

def test_cannot_send_to_closed_conversation(member, admin_user):
    _start(member)
    conversation_id = _conversation(member).public_id
    closed = _client(admin_user).post(f'{ADMIN_BASE}conversations/{conversation_id}/close/')
    assert closed.status_code == 200
    again = _client(admin_user).post(f'{ADMIN_BASE}conversations/{conversation_id}/close/')
    assert again.status_code == 200  # idempotent
    response = _client(member).post(f'{MEMBER_BASE}conversation/messages/', {'text': 'hi'}, format='json')
    assert response.status_code == 409
    assert response.data['errors']['code'] == 'conversation_closed'


# --- attachments -------------------------------------------------------------

def test_image_is_reencoded_and_downloadable_by_participants(member, admin_user):
    _start(member)
    sent = _client(member).post(
        f'{MEMBER_BASE}conversation/attachments/',
        {'type': 'IMAGE', 'file': _png('../../etc/My Photo.jpg', exif=True)},
        format='multipart',
    )
    assert sent.status_code == 201, sent.data
    attachment = sent.data['data']['attachment']
    assert attachment['content_type'] == 'image/jpeg'
    assert attachment['name'] == 'My Photo.jpg'
    message = Message.objects.get(public_id=sent.data['data']['id'])
    assert message.attachment.name.startswith('support/')
    with Image.open(message.attachment.storage.open(message.attachment.name)) as stored:
        assert not stored.getexif()

    for client, url in (
        (_client(member), f'{MEMBER_BASE}attachments/{message.public_id}/'),
        (_client(admin_user), f'{ADMIN_BASE}attachments/{message.public_id}/'),
    ):
        response = client.get(url)
        assert response.status_code == 200
        assert response['Cache-Control'] == 'no-store'
        assert response['X-Content-Type-Options'] == 'nosniff'
        b''.join(response.streaming_content)


def test_pdf_file_is_accepted(member, admin_user):
    _start(member)
    sent = _client(member).post(
        f'{MEMBER_BASE}conversation/attachments/', {'type': 'FILE', 'file': _pdf()}, format='multipart'
    )
    assert sent.status_code == 201, sent.data
    assert sent.data['data']['attachment']['content_type'] == 'application/pdf'


@pytest.mark.parametrize('kind, upload', [
    ('IMAGE', SimpleUploadedFile('fake.png', b'not an image', content_type='image/png')),
    ('FILE', SimpleUploadedFile('fake.pdf', b'MZ\x90\x00 executable', content_type='application/pdf')),
    ('FILE', SimpleUploadedFile('run.exe', PDF_BYTES, content_type='application/pdf')),
    ('FILE', SimpleUploadedFile('js.pdf', PDF_BYTES.replace(b'/Catalog', b'/Catalog /OpenAction << /S /JavaScript >>'), content_type='application/pdf')),
    ('IMAGE', SimpleUploadedFile('doc.pdf', PDF_BYTES, content_type='image/png')),
])
def test_invalid_attachments_are_rejected(member, admin_user, kind, upload):
    _start(member)
    response = _client(member).post(
        f'{MEMBER_BASE}conversation/attachments/', {'type': kind, 'file': upload}, format='multipart'
    )
    assert response.status_code == 400
    assert Message.objects.count() == 0


def test_oversized_attachment_is_rejected(member, admin_user):
    _start(member)
    big = PDF_BYTES + b'0' * MAX_ATTACHMENT_BYTES
    response = _client(member).post(
        f'{MEMBER_BASE}conversation/attachments/', {'type': 'FILE', 'file': _pdf(data=big)}, format='multipart'
    )
    assert response.status_code == 400
    assert Message.objects.count() == 0


def test_oversized_image_dimensions_are_rejected(member, admin_user):
    _start(member)
    response = _client(member).post(
        f'{MEMBER_BASE}conversation/attachments/',
        {'type': 'IMAGE', 'file': _png(size=(8193, 1), mode='L')},
        format='multipart',
    )
    assert response.status_code == 400


# --- unread / read -------------------------------------------------------------

def test_unread_and_mark_read(member, admin_user):
    _start(member)
    conversation_id = _conversation(member).public_id
    admin_client = _client(admin_user)
    first = admin_client.post(f'{ADMIN_BASE}conversations/{conversation_id}/messages/', {'text': 'a'}, format='json')
    admin_client.post(f'{ADMIN_BASE}conversations/{conversation_id}/messages/', {'text': 'b'}, format='json')

    member_client = _client(member)
    assert member_client.get(f'{MEMBER_BASE}conversation/').data['data']['conversation']['unread_count'] == 2
    # The member's own messages never count as unread for them.
    member_client.post(f'{MEMBER_BASE}conversation/messages/', {'text': 'c'}, format='json')

    read = member_client.post(f'{MEMBER_BASE}conversation/read/', {'up_to': first.data['data']['id']}, format='json')
    assert read.data['data']['conversation']['unread_count'] == 1

    latest = member_client.get(f'{MEMBER_BASE}conversation/messages/').data['data']['messages'][-1]
    member_client.post(f'{MEMBER_BASE}conversation/read/', {'up_to': latest['id']}, format='json')
    # Moving the marker back is ignored.
    again = member_client.post(f'{MEMBER_BASE}conversation/read/', {'up_to': first.data['data']['id']}, format='json')
    assert again.data['data']['conversation']['unread_count'] == 0

    # The admin still has the member's message unread.
    detail = admin_client.get(f'{ADMIN_BASE}conversations/{conversation_id}/')
    assert detail.data['data']['unread_count'] == 1


def test_mark_read_rejects_message_from_other_conversation(member, other_member, admin_user):
    _start(member)
    _start(other_member)
    foreign = services.send_member_text(user=other_member, text='x')
    response = _client(member).post(
        f'{MEMBER_BASE}conversation/read/', {'up_to': str(foreign.public_id)}, format='json'
    )
    assert response.status_code == 400
    assert response.data['errors']['code'] == 'invalid_cursor'


def test_after_cursor_returns_only_newer_messages(member, admin_user):
    _start(member)
    first = services.send_member_text(user=member, text='1')
    services.send_member_text(user=member, text='2')
    services.send_member_text(user=member, text='3')
    response = _client(member).get(f'{MEMBER_BASE}conversation/messages/?after={first.public_id}&limit=1')
    data = response.data['data']
    assert [m['text'] for m in data['messages']] == ['2']
    assert data['has_more'] is True
