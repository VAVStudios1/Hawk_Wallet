from django.conf import settings
from django.http import JsonResponse

from appupdates.services import current_update_policy, is_version_supported
from appupdates.versioning import AppVersion, InvalidVersion

APP_VERSION_HEADER = 'X-App-Version'
UPDATE_REQUIRED_CODE = 'app_update_required'
UPDATE_REQUIRED_MESSAGE = 'Please update the app to continue.'


class AppVersionMiddleware:
    """
    Rejects API requests from app versions below the published release's
    minimum_supported_version with 426 and code `app_update_required`.

    Every path under APP_VERSION_ENFORCED_PREFIXES is covered (so new
    endpoints are covered by default) except APP_VERSION_EXEMPT_PREFIXES -
    auth/login/password reset and the update API itself - so an outdated app
    can still sign in, recover its account and download the update.

    The X-App-Version header is a client claim, used only for this
    compatibility check - never for authorization. When no usable release
    is published nothing is enforced.
    """

    def __init__(self, get_response):
        self.get_response = get_response

    def __call__(self, request):
        if self._is_enforced(request.path_info):
            rejection = self._reject_if_outdated(request)
            if rejection is not None:
                return rejection
        return self.get_response(request)

    @staticmethod
    def _is_enforced(path):
        return path.startswith(tuple(settings.APP_VERSION_ENFORCED_PREFIXES)) and not path.startswith(
            tuple(settings.APP_VERSION_EXEMPT_PREFIXES)
        )

    @staticmethod
    def _reject_if_outdated(request):
        raw = (request.headers.get(APP_VERSION_HEADER) or '').strip()
        if not raw and not settings.APP_VERSION_HEADER_REQUIRED:
            return None
        policy = current_update_policy()
        if policy is None:
            return None
        try:
            version = AppVersion.parse(raw)
        except InvalidVersion:
            version = None  # missing (when required) or malformed: treat as outdated
        if version is not None and is_version_supported(version, policy):
            return None
        return JsonResponse(
            {
                'success': False,
                'message': UPDATE_REQUIRED_MESSAGE,
                'errors': {
                    'code': UPDATE_REQUIRED_CODE,
                    'minimum_supported_version': str(policy.minimum_supported_version),
                    'latest_version': str(policy.latest_version),
                },
            },
            status=426,
        )
