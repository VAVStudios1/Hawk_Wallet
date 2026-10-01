class DashboardError(Exception):
    """Base for admin-dashboard errors. `code` is stable for API mapping."""

    code = 'dashboard_error'
    default_message = 'The admin dashboard could not be loaded.'

    def __init__(self, message=None):
        super().__init__(message or self.default_message)


class DashboardPermissionDenied(DashboardError):
    code = 'permission_denied'
    default_message = 'Only active admins have an admin dashboard.'


class InvalidReportQuery(DashboardError):
    code = 'invalid_query'
    default_message = 'The report query is invalid.'


class SystemReportPermissionDenied(DashboardError):
    code = 'permission_denied'
    default_message = 'Only an active Super Admin can view the system report.'
