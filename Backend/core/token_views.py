from drf_spectacular.utils import extend_schema
from rest_framework import serializers, status
from rest_framework.permissions import AllowAny

from core.api import ServiceAPIView
from core.tokens import (
    AuthTokenError,
    InvalidRefreshToken,
    SessionIdleExpired,
    blacklist_refresh_token,
    rotate_refresh_token,
)
from core.utils import error_response, success_response


class RefreshTokenSerializer(serializers.Serializer):
    refresh = serializers.CharField()


class TokenRefreshRequestSerializer(RefreshTokenSerializer):
    # Seconds since the user last interacted with the app. Send it when the
    # refresh follows real use; omit it for background work (e.g. polling),
    # which must not keep an idle session alive.
    idle_seconds = serializers.IntegerField(min_value=0, required=False)


class TokenPairSerializer(serializers.Serializer):
    access = serializers.CharField()
    refresh = serializers.CharField()


class TokenRefreshView(ServiceAPIView):
    """
    Deliberately anonymous: the refresh token in the body is the credential.
    No authentication classes, so an expired access token sent in the
    header cannot block the refresh. Anonymous throttle rate applies.
    A session idle for SESSION_IDLE_TIMEOUT is refused with code
    `session_expired`.
    """

    permission_classes = [AllowAny]
    authentication_classes = []
    service_error = AuthTokenError
    error_status = {
        InvalidRefreshToken: status.HTTP_401_UNAUTHORIZED,
        SessionIdleExpired: status.HTTP_401_UNAUTHORIZED,
    }

    @extend_schema(request=TokenRefreshRequestSerializer, responses={200: TokenPairSerializer})
    def post(self, request):
        serializer = TokenRefreshRequestSerializer(data=request.data)
        if not serializer.is_valid():
            return error_response('Token refresh failed.', errors=serializer.errors)

        tokens = rotate_refresh_token(
            serializer.validated_data['refresh'],
            idle_seconds=serializer.validated_data.get('idle_seconds'),
        )
        return success_response('Token refreshed.', data=tokens)


class LogoutView(ServiceAPIView):
    """
    Blacklists the submitted refresh token (this device's session).

    Deliberately anonymous, like TokenRefreshView: the refresh token is the
    credential, and revoking it grants nothing its holder could not already
    do with it. This lets a client log out after its access token expired.
    """

    permission_classes = [AllowAny]
    authentication_classes = []
    service_error = AuthTokenError
    error_status = {
        InvalidRefreshToken: status.HTTP_400_BAD_REQUEST,
        SessionIdleExpired: status.HTTP_400_BAD_REQUEST,
    }

    @extend_schema(request=RefreshTokenSerializer, responses={200: None})
    def post(self, request):
        serializer = RefreshTokenSerializer(data=request.data)
        if not serializer.is_valid():
            return error_response('Logout failed.', errors=serializer.errors)

        blacklist_refresh_token(serializer.validated_data['refresh'])
        return success_response('Logged out.')
