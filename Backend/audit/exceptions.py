class AuditLogError(Exception):
    """Base for audit-log read errors. `code` is stable for API mapping."""

    code = 'audit_log_error'
    default_message = 'The audit log could not be loaded.'

    def __init__(self, message=None):
        super().__init__(message or self.default_message)


class AuditLogPermissionDenied(AuditLogError):
    code = 'permission_denied'
    default_message = 'Only an active Super Admin can read the audit log.'


class AuditEventNotFound(AuditLogError):
    code = 'audit_event_not_found'
    default_message = 'Audit event not found.'


class InvalidAuditQuery(AuditLogError):
    code = 'invalid_query'
    default_message = 'The audit log query is invalid.'
