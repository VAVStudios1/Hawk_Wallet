from django.http import FileResponse
from drf_spectacular.types import OpenApiTypes
from drf_spectacular.utils import OpenApiParameter, OpenApiResponse, extend_schema
from rest_framework import status
from rest_framework.permissions import AllowAny
from rest_framework.views import APIView

from appupdates.serializers import UpdateCheckSerializer
from appupdates.services import check_for_update, published_release
from appupdates.validators import APK_CONTENT_TYPE
from appupdates.versioning import AppVersion, InvalidVersion
from core.utils import error_response, success_response


class PublicAppAPIView(APIView):
    """
    Deliberately public: an app too old to use the API (or with an expired
    session) must still be able to check for and download the update. No
    authentication runs, so a stale bearer token cannot cause a 401 here.
    Anonymous throttling still applies.
    """

    authentication_classes = []
    permission_classes = [AllowAny]


class UpdateCheckView(PublicAppAPIView):
    @extend_schema(
        parameters=[
            OpenApiParameter('version', OpenApiTypes.STR, required=True, description='Installed app version, e.g. 1.0.5.'),
        ],
        responses={200: UpdateCheckSerializer, 400: OpenApiResponse(description='invalid_version')},
    )
    def get(self, request):
        try:
            version = AppVersion.parse(request.query_params.get('version', '').strip())
        except InvalidVersion:
            return error_response(
                'Enter the installed version as MAJOR.MINOR.PATCH, e.g. 1.0.5.',
                errors={'code': 'invalid_version'},
            )
        return success_response('Update status retrieved.', UpdateCheckSerializer(check_for_update(version)).data)


class ApkDownloadView(PublicAppAPIView):
    @extend_schema(responses={(200, APK_CONTENT_TYPE): OpenApiTypes.BINARY, 404: OpenApiResponse(description='release_not_found')})
    def get(self, request, version):
        release = published_release(version)
        if release is None or not release.apk_file.storage.exists(release.apk_file.name):
            return error_response(
                'This release is not available.',
                errors={'code': 'release_not_found'},
                status_code=status.HTTP_404_NOT_FOUND,
            )
        response = FileResponse(
            release.apk_file.open('rb'),
            as_attachment=True,
            filename=f'HawkWallet-{release.version}.apk',
            content_type=APK_CONTENT_TYPE,
        )
        response['Cache-Control'] = 'no-cache'
        return response
