class HomeError(Exception):
    """Base for home-dashboard errors. `code` is stable for API mapping."""

    code = 'home_error'
    default_message = 'The home screen could not be loaded.'

    def __init__(self, message=None):
        super().__init__(message or self.default_message)


class HomePermissionDenied(HomeError):
    code = 'permission_denied'
    default_message = 'Only verified members have a home screen.'


class InvalidTransactionQuery(HomeError):
    code = 'invalid_query'
    default_message = 'The transaction history query is invalid.'
