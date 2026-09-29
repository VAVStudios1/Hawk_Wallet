from drf_spectacular.utils import extend_schema
from rest_framework import serializers, status
from rest_framework.permissions import AllowAny, IsAuthenticated

from core.api import ServiceAPIView
from core.tokens import (
    AuthTokenError,
    InvalidRefreshToken,
    RefreshTokenNotOwned,
    blacklist_own_refresh_token,
    rotate_refresh_token,
)
from core.utils import error_response, success_response


class RefreshTokenSerializer(serializers.Serializer):
    refresh = serializers.CharField()


class TokenPairSerializer(serializers.Serializer):
    access = serializers.CharField()
    refresh = serializers.CharField()


class TokenRefreshView(ServiceAPIView):
    """
    Deliberately anonymous: the refresh token in the body is the credential.
    No authentication classes, so an expired access token sent in the
    header cannot block the refresh. Anonymous throttle rate applies.
    """

    permission_classes = [AllowAny]
    authentication_classes = []
    service_error = AuthTokenError
    error_status = {InvalidRefreshToken: status.HTTP_401_UNAUTHORIZED}

    @extend_schema(request=RefreshTokenSerializer, responses={200: TokenPairSerializer})
    def post(self, request):
        serializer = RefreshTokenSerializer(data=request.data)
        if not serializer.is_valid():
            return error_response('Token refresh failed.', errors=serializer.errors)

        tokens = rotate_refresh_token(serializer.validated_data['refresh'])
        return success_response('Token refreshed.', data=tokens)


class LogoutView(ServiceAPIView):
    """Blacklists the caller's own refresh token (this device's session)."""

    permission_classes = [IsAuthenticated]
    service_error = AuthTokenError
    error_status = {
        InvalidRefreshToken: status.HTTP_400_BAD_REQUEST,
        RefreshTokenNotOwned: status.HTTP_403_FORBIDDEN,
    }

    @extend_schema(request=RefreshTokenSerializer, responses={200: None})
    def post(self, request):
        serializer = RefreshTokenSerializer(data=request.data)
        if not serializer.is_valid():
            return error_response('Logout failed.', errors=serializer.errors)

        blacklist_own_refresh_token(
            user=request.user, raw_token=serializer.validated_data['refresh']
        )
        return success_response('Logged out.')
