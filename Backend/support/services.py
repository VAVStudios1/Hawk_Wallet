"""
Support chat services. Views only validate input shape and call these;
every access rule is enforced here.

Access (user decisions 2026-10-01):
- MEMBER: only their own conversations. They never pass a conversation
  id - the current conversation is always resolved from the user.
- ADMIN: only conversations assigned to them; they may reply, mark read
  and close.
- SUPER_ADMIN: may view every conversation across all admins, strictly
  read-only by role: never sends, marks read or closes any conversation.
  Viewing a conversation or attachment assigned to another admin is
  audited. SUPER_ADMINs are not in the routing pool.
Conversations the caller may not see raise ConversationNotFound, never a
permission error, so their existence is not revealed.

Routing (user decision 2026-10-01): a member's first conversation goes to
the active ADMIN with the fewest OPEN conversations (ties: lowest id).
Every later conversation of that member goes to the admin of their
previous conversation. The assignment is stored on the conversation and
never changes.

Ordering: sending a message locks its conversation row, so within one
conversation messages are created (and committed) in created_at order.
That keeps `after=` polling and the read watermarks from skipping a
message committed late.

Chat is a support feature only; nothing here touches orders, pay-ins,
payouts, payment methods, commissions or balances.
"""
import logging
from dataclasses import dataclass
from uuid import UUID

from django.core.exceptions import ValidationError
from django.core.paginator import EmptyPage, PageNotAnInteger, Paginator
from django.db import IntegrityError, transaction
from django.db.models import Count, F, Q
from django.utils import timezone

from audit.models import AuditEvent
from audit.services import record_audit_event
from core.models import User
from core.permissions import is_active_admin, is_verified_member
from support.attachments import sanitize_image, sanitize_pdf
from support.exceptions import (
    AttachmentNotFound,
    ConversationClosed,
    ConversationNotFound,
    ConversationReadOnly,
    InvalidAttachment,
    InvalidCursor,
    InvalidMessage,
    InvalidPage,
    InvalidStatusFilter,
    SupportConflict,
    SupportPermissionDenied,
    SupportUnavailable,
)
from support.models import MESSAGE_TEXT_MAX_LENGTH, Conversation, Message

logger = logging.getLogger(__name__)

MESSAGE_PAGE_SIZE = 50
MESSAGE_MAX_PAGE_SIZE = 100
CONVERSATION_PAGE_SIZE = 50
CONVERSATION_MAX_PAGE_SIZE = 100

ROUTING_LEAST_LOADED = 'least_loaded'
ROUTING_PREVIOUS_ADMIN = 'previous_admin'


@dataclass(frozen=True)
class MessagePage:
    messages: list
    has_more: bool


@dataclass(frozen=True)
class AttachmentFile:
    file: object
    content_type: str
    name: str


# --- permission helpers -------------------------------------------------------

def _require_member(user):
    if not is_verified_member(user):
        raise SupportPermissionDenied('Only verified members can use support chat.')


def _require_admin(user):
    if not is_active_admin(user):
        raise SupportPermissionDenied('Only active admins can view support conversations.')


def _is_super_admin(user):
    return user.role == User.Role.SUPER_ADMIN


def _as_uuid(value):
    if isinstance(value, UUID):
        return value
    try:
        return UUID(str(value))
    except (TypeError, ValueError, AttributeError):
        return None


def _with_unread(queryset, side):
    """
    Annotate unread_count: messages from the other side newer than this
    side's watermark. `side` is 'user' (member unread) or 'admin'.
    """
    if side == 'user':
        from_other = ~Q(messages__sender=F('user'))
        watermark = 'user_last_read_at'
    else:
        from_other = Q(messages__sender=F('user'))
        watermark = 'admin_last_read_at'
    newer = Q(**{f'{watermark}__isnull': True}) | Q(messages__created_at__gt=F(watermark))
    return queryset.annotate(unread_count=Count('messages', filter=from_other & newer))


def _audit(actor, action, conversation, ip_address, **metadata):
    record_audit_event(
        actor=actor,
        action=action,
        target=conversation,
        ip_address=ip_address,
        metadata={'conversation_id': str(conversation.public_id), **metadata},
    )


# --- routing ------------------------------------------------------------------

def _least_loaded_admin():
    """
    The active ADMIN with the fewest OPEN conversations; ties go to the
    lowest id. SUPER_ADMINs are read-only in chat and never assigned.
    """
    return (
        User.objects.filter(role=User.Role.ADMIN, is_active=True)
        .annotate(
            open_count=Count(
                'assigned_support_conversations',
                filter=Q(assigned_support_conversations__status=Conversation.Status.OPEN),
            )
        )
        .order_by('open_count', 'id')
        .first()
    )


def _route(user):
    """Return (admin, routing) for a new conversation of `user`."""
    previous = (
        Conversation.objects.filter(user=user)
        .select_related('assigned_admin')
        .order_by('-created_at', '-id')
        .first()
    )
    if previous is not None:
        admin = previous.assigned_admin
        # The previous admin can no longer act on chats (deactivated or no
        # longer ADMIN): fall back to least-loaded rather than leave the
        # member with a conversation nobody can answer.
        if admin.is_active and admin.role == User.Role.ADMIN:
            return admin, ROUTING_PREVIOUS_ADMIN
    admin = _least_loaded_admin()
    if admin is None:
        raise SupportUnavailable()
    return admin, ROUTING_LEAST_LOADED


# --- member -------------------------------------------------------------------

def _current_conversation(user):
    # The OPEN conversation if any, else the most recent closed one. Only
    # one can be OPEN and it is always the newest.
    return (
        _with_unread(Conversation.objects.filter(user=user), 'user')
        .order_by('-created_at', '-id')
        .first()
    )


def get_member_conversation(*, user):
    """The member's current conversation (annotated with unread_count), or None."""
    _require_member(user)
    return _current_conversation(user)


def start_conversation(*, user, ip_address=None):
    """
    Return (conversation, created). An existing OPEN conversation is
    returned as is; otherwise a new one is created and routed. Safe to
    retry: the one-open-per-user constraint is the final guard.
    """
    _require_member(user)
    try:
        with transaction.atomic():
            # Serializes concurrent starts by the same member.
            User.objects.select_for_update().only('pk').get(pk=user.pk)
            existing = Conversation.objects.filter(
                user=user, status=Conversation.Status.OPEN
            ).first()
            if existing is not None:
                return _current_conversation(user), False

            admin, routing = _route(user)
            conversation = Conversation.objects.create(user=user, assigned_admin=admin)
            _audit(
                user,
                AuditEvent.Action.SUPPORT_CONVERSATION_STARTED,
                conversation,
                ip_address,
                assigned_admin_id=admin.pk,
                routing=routing,
            )
    except IntegrityError:
        existing = _current_conversation(user)
        if existing is not None and existing.status == Conversation.Status.OPEN:
            return existing, False
        raise SupportConflict()
    return _current_conversation(user), True


def _require_member_conversation(user):
    conversation = _current_conversation(user)
    if conversation is None:
        raise ConversationNotFound()
    return conversation


def list_member_messages(*, user, before=None, after=None, limit=None):
    _require_member(user)
    conversation = _require_member_conversation(user)
    return _list_messages(conversation, before=before, after=after, limit=limit)


def send_member_text(*, user, text):
    _require_member(user)
    conversation = _require_member_conversation(user)
    return _send_text(conversation, user, text)


def send_member_attachment(*, user, message_type, upload, caption=''):
    _require_member(user)
    conversation = _require_member_conversation(user)
    return _send_attachment(conversation, user, message_type, upload, caption)


def mark_member_read(*, user, up_to):
    _require_member(user)
    conversation = _require_member_conversation(user)
    return _mark_read(conversation, 'user_last_read_at', up_to)


def open_member_attachment(*, user, message_id):
    """An attachment from any of the member's own conversations."""
    _require_member(user)
    message = _attachment_message(Message.objects.filter(conversation__user=user), message_id)
    return _open_attachment(message)


# --- admin --------------------------------------------------------------------

def _visible_conversations(admin):
    if _is_super_admin(admin):
        return Conversation.objects.all()
    return Conversation.objects.filter(assigned_admin=admin)


def _get_visible_conversation(admin, conversation_id):
    public_id = _as_uuid(conversation_id)
    conversation = (
        _with_unread(_visible_conversations(admin), 'admin')
        .filter(public_id=public_id)
        .select_related('user')
        .first()
        if public_id
        else None
    )
    if conversation is None:
        raise ConversationNotFound()
    return conversation


def _get_assigned_conversation(admin, conversation_id):
    """
    A conversation the admin may act on (send, mark read, close): only an
    ADMIN, and only their own. A SUPER_ADMIN is strictly read-only by role,
    even on a conversation that happens to be assigned to them (e.g. an
    ADMIN later promoted).
    """
    conversation = _get_visible_conversation(admin, conversation_id)
    if _is_super_admin(admin) or conversation.assigned_admin_id != admin.pk:
        raise ConversationReadOnly()
    return conversation


def list_admin_conversations(*, admin, status=None, page=None, page_size=None):
    """
    ADMIN: their assigned conversations. SUPER_ADMIN: all conversations.
    Most recent activity first; conversations with no message yet sort by
    creation. Returns (conversations, meta).
    """
    _require_admin(admin)
    queryset = _with_unread(_visible_conversations(admin), 'admin').select_related('user')
    if status not in (None, ''):
        if status not in Conversation.Status.values:
            raise InvalidStatusFilter()
        queryset = queryset.filter(status=status)
    queryset = queryset.order_by(
        F('last_message_at').desc(nulls_last=True), '-created_at', '-id'
    )
    return _paginate(queryset, page, page_size)


def get_admin_conversation(*, admin, conversation_id, ip_address=None):
    """
    One visible conversation. A SUPER_ADMIN opening a conversation assigned
    to another admin records one SUPPORT_CONVERSATION_VIEWED AuditEvent.
    """
    _require_admin(admin)
    conversation = _get_visible_conversation(admin, conversation_id)
    if conversation.assigned_admin_id != admin.pk:
        _audit(admin, AuditEvent.Action.SUPPORT_CONVERSATION_VIEWED, conversation, ip_address)
    return conversation


def list_admin_messages(*, admin, conversation_id, before=None, after=None, limit=None):
    # Not audited per call (polling); opening the conversation is.
    _require_admin(admin)
    conversation = _get_visible_conversation(admin, conversation_id)
    return _list_messages(conversation, before=before, after=after, limit=limit)


def send_admin_text(*, admin, conversation_id, text):
    _require_admin(admin)
    conversation = _get_assigned_conversation(admin, conversation_id)
    return _send_text(conversation, admin, text)


def send_admin_attachment(*, admin, conversation_id, message_type, upload, caption=''):
    _require_admin(admin)
    conversation = _get_assigned_conversation(admin, conversation_id)
    return _send_attachment(conversation, admin, message_type, upload, caption)


def mark_admin_read(*, admin, conversation_id, up_to):
    _require_admin(admin)
    conversation = _get_assigned_conversation(admin, conversation_id)
    return _mark_read(conversation, 'admin_last_read_at', up_to)


def close_conversation(*, admin, conversation_id, ip_address=None):
    """
    Close an OPEN conversation; only its assigned admin may. Closing an
    already closed conversation returns it unchanged (safe to retry).
    """
    _require_admin(admin)
    conversation = _get_assigned_conversation(admin, conversation_id)
    with transaction.atomic():
        locked = Conversation.objects.select_for_update().get(pk=conversation.pk)
        if locked.status == Conversation.Status.CLOSED:
            return _get_visible_conversation(admin, conversation_id)
        locked.status = Conversation.Status.CLOSED
        locked.closed_at = timezone.now()
        locked.save(update_fields=['status', 'closed_at', 'updated_at'])
        _audit(admin, AuditEvent.Action.SUPPORT_CONVERSATION_CLOSED, locked, ip_address)
    return _get_visible_conversation(admin, conversation_id)


def open_admin_attachment(*, admin, message_id, ip_address=None):
    """
    An attachment in a visible conversation. A SUPER_ADMIN opening one from
    a conversation assigned to another admin records one
    SUPPORT_ATTACHMENT_VIEWED AuditEvent.
    """
    _require_admin(admin)
    message = _attachment_message(
        Message.objects.filter(conversation__in=_visible_conversations(admin)), message_id
    )
    attachment = _open_attachment(message)
    if message.conversation.assigned_admin_id != admin.pk:
        try:
            _audit(
                admin,
                AuditEvent.Action.SUPPORT_ATTACHMENT_VIEWED,
                message.conversation,
                ip_address,
                message_id=str(message.public_id),
            )
        except BaseException:
            attachment.file.close()
            raise
    return attachment


# --- shared -------------------------------------------------------------------

def _paginate(queryset, page, page_size):
    try:
        size = int(page_size) if page_size not in (None, '') else CONVERSATION_PAGE_SIZE
    except (TypeError, ValueError):
        raise InvalidPage('page_size must be a whole number from 1 to 100.')
    if not 1 <= size <= CONVERSATION_MAX_PAGE_SIZE:
        raise InvalidPage('page_size must be a whole number from 1 to 100.')
    paginator = Paginator(queryset, size)
    try:
        current = paginator.page(page or 1)
    except (EmptyPage, PageNotAnInteger):
        raise InvalidPage()
    return list(current.object_list), {
        'page': current.number,
        'page_size': size,
        'total': paginator.count,
        'has_next': current.has_next(),
    }


def _cursor_message(conversation, message_id):
    public_id = _as_uuid(message_id)
    message = (
        Message.objects.filter(conversation=conversation, public_id=public_id).first()
        if public_id
        else None
    )
    if message is None:
        raise InvalidCursor()
    return message


def _list_messages(conversation, *, before=None, after=None, limit=None):
    """
    Messages oldest first. No cursor: the latest `limit`. before=<id>: the
    `limit` just older than that message (history). after=<id>: the
    `limit` just newer (polling). has_more says whether more exist beyond
    the returned page in the direction being read.
    """
    try:
        size = int(limit) if limit not in (None, '') else MESSAGE_PAGE_SIZE
    except (TypeError, ValueError):
        raise InvalidCursor('limit must be a whole number from 1 to 100.')
    if not 1 <= size <= MESSAGE_MAX_PAGE_SIZE:
        raise InvalidCursor('limit must be a whole number from 1 to 100.')
    if before not in (None, '') and after not in (None, ''):
        raise InvalidCursor()

    messages = Message.objects.filter(conversation=conversation).select_related('conversation')
    if after not in (None, ''):
        cursor = _cursor_message(conversation, after)
        newer = messages.filter(
            Q(created_at__gt=cursor.created_at)
            | Q(created_at=cursor.created_at, id__gt=cursor.id)
        ).order_by('created_at', 'id')
        rows = list(newer[: size + 1])
        return MessagePage(messages=rows[:size], has_more=len(rows) > size)

    if before not in (None, ''):
        cursor = _cursor_message(conversation, before)
        messages = messages.filter(
            Q(created_at__lt=cursor.created_at)
            | Q(created_at=cursor.created_at, id__lt=cursor.id)
        )
    rows = list(messages.order_by('-created_at', '-id')[: size + 1])
    return MessagePage(messages=list(reversed(rows[:size])), has_more=len(rows) > size)


def _clean_text(text, *, required):
    if not isinstance(text, str):
        raise InvalidMessage(errors={'text': ['Text must be a string.']})
    text = text.strip()
    if required and not text:
        raise InvalidMessage(errors={'text': ['Message text is required.']})
    if len(text) > MESSAGE_TEXT_MAX_LENGTH:
        raise InvalidMessage(
            errors={'text': [f'Messages must be at most {MESSAGE_TEXT_MAX_LENGTH} characters.']}
        )
    return text


def _lock_open_conversation(conversation):
    locked = Conversation.objects.select_for_update().get(pk=conversation.pk)
    if locked.status != Conversation.Status.OPEN:
        raise ConversationClosed()
    return locked


def _touch(conversation, message):
    # update(): only activity fields, never the immutable assignment.
    Conversation.objects.filter(pk=conversation.pk).update(
        last_message_at=message.created_at, updated_at=timezone.now()
    )


def _send_text(conversation, sender, text):
    text = _clean_text(text, required=True)
    with transaction.atomic():
        locked = _lock_open_conversation(conversation)
        message = Message.objects.create(
            conversation=locked, sender=sender, type=Message.Type.TEXT, text=text
        )
        _touch(locked, message)
    return message


def _send_attachment(conversation, sender, message_type, upload, caption):
    caption = _clean_text(caption or '', required=False)
    if upload is None:
        raise InvalidAttachment(errors={'file': ['A file is required.']})
    try:
        if message_type == Message.Type.IMAGE:
            clean = sanitize_image(upload)
        elif message_type == Message.Type.FILE:
            clean = sanitize_pdf(upload)
        else:
            raise InvalidAttachment(errors={'type': ['type must be IMAGE or FILE.']})
    except ValidationError as exc:
        # Why an upload was refused (validator messages and upload metadata
        # only - never file content), so rejects can be diagnosed.
        logger.warning(
            'Support attachment rejected: type=%s name=%r size=%s content_type=%s reasons=%s',
            message_type, getattr(upload, 'name', None), getattr(upload, 'size', None),
            getattr(upload, 'content_type', None), exc.messages,
        )
        raise InvalidAttachment(errors={'file': exc.messages})

    field = Message._meta.get_field('attachment')
    stored_name = None
    try:
        with transaction.atomic():
            locked = _lock_open_conversation(conversation)
            stored_name = field.storage.save(
                field.generate_filename(None, clean.content.name), clean.content
            )
            message = Message.objects.create(
                conversation=locked,
                sender=sender,
                type=message_type,
                text=caption,
                attachment=stored_name,
                attachment_name=clean.display_name,
                attachment_content_type=clean.content_type,
                attachment_size=clean.size,
                attachment_sha256=clean.sha256,
            )
            _touch(locked, message)
    except BaseException:
        if stored_name:
            field.storage.delete(stored_name)
        raise
    return message


def _mark_read(conversation, watermark, up_to):
    """
    Move `watermark` forward to message `up_to` (one the client has
    received). Never moves it back, so late or repeated calls are harmless.
    Returns the conversation re-read with its unread_count.
    """
    message = _cursor_message(conversation, up_to)
    Conversation.objects.filter(pk=conversation.pk).filter(
        Q(**{f'{watermark}__isnull': True}) | Q(**{f'{watermark}__lt': message.created_at})
    ).update(**{watermark: message.created_at})
    side = 'user' if watermark == 'user_last_read_at' else 'admin'
    return _with_unread(Conversation.objects.filter(pk=conversation.pk), side).select_related('user').get()


def _attachment_message(queryset, message_id):
    public_id = _as_uuid(message_id)
    message = (
        queryset.filter(public_id=public_id)
        .exclude(attachment='')
        .select_related('conversation')
        .first()
        if public_id
        else None
    )
    if message is None:
        raise AttachmentNotFound()
    return message


def _open_attachment(message):
    try:
        file = message.attachment.storage.open(message.attachment.name, 'rb')
    except (FileNotFoundError, ValueError):
        raise AttachmentNotFound()
    return AttachmentFile(
        file=file, content_type=message.attachment_content_type, name=message.attachment_name
    )
