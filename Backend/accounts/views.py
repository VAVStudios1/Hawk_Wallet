from drf_spectacular.utils import extend_schema
from rest_framework import status
from rest_framework.throttling import ScopedRateThrottle

from accounts.exceptions import ProfileError, ProfilePermissionDenied
from accounts.serializers import (
    ChangePasswordSerializer,
    ProfileSerializer,
    UpdateProfileSerializer,
)
from accounts.services import change_password, get_profile, update_profile_name
from core.api import ServiceAPIView, client_ip
from core.permissions import IsVerifiedUser
from core.token_views import TokenPairSerializer
from core.utils import error_response, success_response


class ProfileAPIView(ServiceAPIView):
    """Any verified user, any role; always request.user's own profile."""

    permission_classes = [IsVerifiedUser]
    service_error = ProfileError
    error_status = {ProfilePermissionDenied: status.HTTP_403_FORBIDDEN}


class ProfileView(ProfileAPIView):
    @extend_schema(responses={200: ProfileSerializer})
    def get(self, request):
        profile = get_profile(user=request.user)
        return success_response('Profile.', data=ProfileSerializer(profile).data)

    @extend_schema(request=UpdateProfileSerializer, responses={200: ProfileSerializer})
    def patch(self, request):
        serializer = UpdateProfileSerializer(data=request.data)
        if not serializer.is_valid():
            return error_response('Profile details are invalid.', errors=serializer.errors)

        profile = update_profile_name(
            user=request.user,
            name=serializer.validated_data['name'],
            ip_address=client_ip(request),
        )
        return success_response('Profile updated.', data=ProfileSerializer(profile).data)


class ChangePasswordView(ProfileAPIView):
    # Stricter than the per-user default: this endpoint checks a password.
    throttle_classes = [ScopedRateThrottle]
    throttle_scope = 'password_change'

    @extend_schema(request=ChangePasswordSerializer, responses={200: TokenPairSerializer})
    def post(self, request):
        serializer = ChangePasswordSerializer(data=request.data)
        if not serializer.is_valid():
            return error_response('Password change failed.', errors=serializer.errors)

        tokens = change_password(
            user=request.user,
            current_password=serializer.validated_data['current_password'],
            new_password=serializer.validated_data['new_password'],
            ip_address=client_ip(request),
        )
        return success_response(
            'Password changed. You have been signed out on other devices.', data=tokens
        )
