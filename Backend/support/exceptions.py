class SupportError(Exception):
    """Base for support chat service errors. `code` is stable for API mapping."""

    code = 'support_error'
    default_message = 'The support request could not be completed.'

    def __init__(self, message=None, errors=None):
        super().__init__(message or self.default_message)
        self.errors = errors or {}


class SupportPermissionDenied(SupportError):
    code = 'permission_denied'
    default_message = 'You do not have access to support chat.'


class ConversationNotFound(SupportError):
    # Also covers conversations the caller may not see, so their existence
    # is never revealed.
    code = 'conversation_not_found'
    default_message = 'Conversation not found.'


class ConversationClosed(SupportError):
    code = 'conversation_closed'
    default_message = 'This conversation is closed. Start a new conversation to continue.'


class ConversationReadOnly(SupportError):
    # A SUPER_ADMIN viewing a conversation assigned to another admin.
    code = 'conversation_read_only'
    default_message = 'You can view this conversation but not act on it.'


class MessageNotFound(SupportError):
    code = 'message_not_found'
    default_message = 'Message not found.'


class AttachmentNotFound(SupportError):
    code = 'attachment_not_found'
    default_message = 'Attachment not found.'


class InvalidMessage(SupportError):
    code = 'invalid_message'
    default_message = 'The message is invalid.'


class InvalidAttachment(SupportError):
    code = 'invalid_attachment'
    default_message = 'The attachment is invalid.'


class InvalidCursor(SupportError):
    code = 'invalid_cursor'
    default_message = 'Use either before or after, with a message from this conversation.'


class InvalidPage(SupportError):
    code = 'invalid_page'
    default_message = 'Page must be a whole number from 1 up to the last page.'


class SupportUnavailable(SupportError):
    code = 'support_unavailable'
    default_message = 'Support is not available right now. Please try again later.'


class SupportConflict(SupportError):
    code = 'conflict'
    default_message = 'The request could not be completed. Please try again.'


class InvalidStatusFilter(SupportError):
    code = 'invalid_status'
    default_message = 'status must be OPEN or CLOSED.'
