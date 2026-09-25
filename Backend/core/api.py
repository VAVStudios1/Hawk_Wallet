from rest_framework import status
from rest_framework.views import APIView

from core.utils import error_response


class ServiceAPIView(APIView):
    """
    Base for views that call a domain service. Service errors (instances of
    `service_error`, each with a stable `code`) become error_response bodies
    with the HTTP status from `error_status` (400 when unlisted). DRF's own
    auth/permission/throttle errors are put in the same envelope.
    """

    service_error = None
    error_status = {}

    def handle_exception(self, exc):
        if self.service_error is not None and isinstance(exc, self.service_error):
            return error_response(
                str(exc),
                errors={'code': exc.code, **getattr(exc, 'errors', {})},
                status_code=self.error_status.get(type(exc), status.HTTP_400_BAD_REQUEST),
            )
        response = super().handle_exception(exc)
        detail = response.data.get('detail') if isinstance(response.data, dict) else None
        if detail is not None:
            response.data = {
                'success': False,
                'message': str(detail),
                'errors': {'code': getattr(detail, 'code', 'error')},
            }
        return response


def client_ip(request):
    return request.META.get('REMOTE_ADDR')
