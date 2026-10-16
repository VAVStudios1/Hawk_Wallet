from django.conf import settings
from django.contrib.auth import get_user_model
from django.db import transaction
from drf_spectacular.utils import extend_schema
from rest_framework import status
from rest_framework.permissions import AllowAny
from rest_framework.throttling import ScopedRateThrottle
from rest_framework.views import APIView
from rest_framework_simplejwt.tokens import RefreshToken

from core.api import ServiceAPIView, client_ip
from core.login import (
    AccountLocked,
    AccountNotVerified,
    InvalidCredentials,
    LoginError,
    login_with_password,
)
from core.models import OTP
from core.password_reset import (
    PasswordResetError,
    confirm_password_reset,
    request_password_reset,
    verify_password_reset_otp,
)
from core.serializers import (
    LoginSerializer,
    PasswordResetConfirmSerializer,
    PasswordResetRequestSerializer,
    PasswordResetVerifySerializer,
    RegisterSerializer,
    ResendOTPSerializer,
    VerifyOTPSerializer,
)
from core.services import (
    OTPCooldownError,
    OTPDeliveryError,
    OTPRequestLimitError,
    OTPServiceError,
    generate_and_send_otp,
    verify_otp,
)
from core.utils import error_response, success_response

User = get_user_model()


def _otp_error_response(exc: OTPServiceError):
    if isinstance(exc, (OTPCooldownError, OTPRequestLimitError)):
        errors = {'code': exc.code}
        if exc.retry_after_seconds is not None:
            errors['retry_after_seconds'] = exc.retry_after_seconds
        return error_response(
            str(exc), errors=errors, status_code=status.HTTP_429_TOO_MANY_REQUESTS
        )
    if isinstance(exc, OTPDeliveryError):
        return error_response(
            str(exc), errors={'code': exc.code}, status_code=status.HTTP_503_SERVICE_UNAVAILABLE
        )
    return error_response(str(exc), status_code=status.HTTP_400_BAD_REQUEST)


class RegisterView(APIView):
    permission_classes = [AllowAny]
    throttle_classes = [ScopedRateThrottle]
    throttle_scope = 'otp'

    def post(self, request):
        serializer = RegisterSerializer(data=request.data)
        if not serializer.is_valid():
            return error_response('Registration failed.', errors=serializer.errors)

        with transaction.atomic():
            user = serializer.save()

        # Sent after the account commits: if the SMS fails, the account stays
        # unverified and the user recovers through resend-otp or login.
        try:
            generate_and_send_otp(user, OTP.Purpose.REGISTRATION)
        except OTPServiceError:
            return success_response(
                'Registration successful, but we could not send the OTP. '
                'Please request a new one shortly.',
                data={'mobile_number': user.mobile_number, 'otp_sent': False},
                status_code=status.HTTP_201_CREATED,
            )

        return success_response(
            'Registration successful. An OTP has been sent to verify your mobile number.',
            data={'mobile_number': user.mobile_number, 'otp_sent': True},
            status_code=status.HTTP_201_CREATED,
        )


class VerifyRegistrationOTPView(APIView):
    permission_classes = [AllowAny]
    throttle_classes = [ScopedRateThrottle]
    throttle_scope = 'otp'

    def post(self, request):
        serializer = VerifyOTPSerializer(data=request.data)
        if not serializer.is_valid():
            return error_response('Verification failed.', errors=serializer.errors)

        mobile_number = serializer.validated_data['mobile_number']
        code = serializer.validated_data['otp']

        try:
            user = User.objects.get(mobile_number=mobile_number)
        except User.DoesNotExist:
            return error_response('Invalid mobile number.', status_code=status.HTTP_404_NOT_FOUND)

        if user.is_verified:
            return error_response('This account is already verified.')

        try:
            verify_otp(user, OTP.Purpose.REGISTRATION, code)
        except OTPServiceError as exc:
            return _otp_error_response(exc)

        user.is_verified = True
        user.save(update_fields=['is_verified'])

        return success_response('Mobile number verified successfully. You can now log in.')


class ResendOTPView(APIView):
    permission_classes = [AllowAny]
    throttle_classes = [ScopedRateThrottle]
    throttle_scope = 'otp'

    def post(self, request):
        serializer = ResendOTPSerializer(data=request.data)
        if not serializer.is_valid():
            return error_response('Resend failed.', errors=serializer.errors)

        mobile_number = serializer.validated_data['mobile_number']
        purpose = serializer.validated_data['purpose']

        try:
            user = User.objects.get(mobile_number=mobile_number)
        except User.DoesNotExist:
            return error_response('Invalid mobile number.', status_code=status.HTTP_404_NOT_FOUND)

        if purpose == OTP.Purpose.REGISTRATION and user.is_verified:
            return error_response('This account is already verified.')

        try:
            generate_and_send_otp(user, purpose)
        except OTPServiceError as exc:
            return _otp_error_response(exc)

        return success_response('A new OTP has been sent.')


class LoginView(ServiceAPIView):
    """
    Password login, no OTP (core/login.py). Anonymous by design; no
    authentication classes, so a stale access token in the header cannot
    block it.
    """

    permission_classes = [AllowAny]
    authentication_classes = []
    throttle_classes = [ScopedRateThrottle]
    throttle_scope = 'login'
    service_error = LoginError
    error_status = {
        InvalidCredentials: status.HTTP_401_UNAUTHORIZED,
        AccountLocked: status.HTTP_429_TOO_MANY_REQUESTS,
        AccountNotVerified: status.HTTP_403_FORBIDDEN,
    }

    def post(self, request):
        serializer = LoginSerializer(data=request.data)
        if not serializer.is_valid():
            return error_response('Login failed.', errors=serializer.errors)

        data = login_with_password(
            mobile_number=serializer.validated_data['mobile_number'],
            password=serializer.validated_data['password'],
            request=request,
            ip_address=client_ip(request),
        )
        return success_response('Login successful.', data=data)


class PasswordResetAPIView(ServiceAPIView):
    """Anonymous password-reset steps (core/password_reset.py)."""

    permission_classes = [AllowAny]
    authentication_classes = []
    throttle_classes = [ScopedRateThrottle]
    throttle_scope = 'otp'
    service_error = PasswordResetError


class PasswordResetRequestView(PasswordResetAPIView):
    @extend_schema(request=PasswordResetRequestSerializer, responses={200: None})
    def post(self, request):
        serializer = PasswordResetRequestSerializer(data=request.data)
        if not serializer.is_valid():
            return error_response('Password reset request failed.', errors=serializer.errors)

        request_password_reset(mobile_number=serializer.validated_data['mobile_number'])
        # Same answer whether or not the number has an account.
        return success_response(
            'If an account exists for this mobile number, an OTP has been sent.'
        )


class PasswordResetVerifyView(PasswordResetAPIView):
    @extend_schema(request=PasswordResetVerifySerializer, responses={200: None})
    def post(self, request):
        serializer = PasswordResetVerifySerializer(data=request.data)
        if not serializer.is_valid():
            return error_response('Verification failed.', errors=serializer.errors)

        reset_token = verify_password_reset_otp(
            mobile_number=serializer.validated_data['mobile_number'],
            code=serializer.validated_data['otp'],
        )
        return success_response(
            'OTP verified. Choose a new password.',
            data={
                'reset_token': reset_token,
                'expires_in_seconds': settings.PASSWORD_RESET_TOKEN_EXPIRY_MINUTES * 60,
            },
        )


class PasswordResetConfirmView(PasswordResetAPIView):
    @extend_schema(request=PasswordResetConfirmSerializer, responses={200: None})
    def post(self, request):
        serializer = PasswordResetConfirmSerializer(data=request.data)
        if not serializer.is_valid():
            return error_response('Password reset failed.', errors=serializer.errors)

        confirm_password_reset(
            reset_token=serializer.validated_data['reset_token'],
            new_password=serializer.validated_data['new_password'],
            ip_address=client_ip(request),
        )
        return success_response('Password reset. Please log in with your new password.')


class VerifyLoginOTPView(APIView):
    """
    Legacy second step of the old OTP login. Password login no longer
    issues login OTPs and resend-otp no longer accepts the login purpose, so
    this endpoint has nothing left to verify. Kept for backward
    compatibility until it is removed.
    """

    permission_classes = [AllowAny]
    throttle_classes = [ScopedRateThrottle]
    throttle_scope = 'otp'

    def post(self, request):
        serializer = VerifyOTPSerializer(data=request.data)
        if not serializer.is_valid():
            return error_response('Verification failed.', errors=serializer.errors)

        mobile_number = serializer.validated_data['mobile_number']
        code = serializer.validated_data['otp']

        try:
            user = User.objects.get(mobile_number=mobile_number)
        except User.DoesNotExist:
            return error_response('Invalid mobile number.', status_code=status.HTTP_404_NOT_FOUND)

        if not user.is_verified:
            return error_response(
                'Please complete registration verification first.',
                status_code=status.HTTP_403_FORBIDDEN,
            )

        try:
            verify_otp(user, OTP.Purpose.LOGIN, code)
        except OTPServiceError as exc:
            return _otp_error_response(exc)

        refresh = RefreshToken.for_user(user)

        return success_response(
            'Login successful.',
            data={
                'access': str(refresh.access_token),
                'refresh': str(refresh),
                'user': {
                    'id': user.id,
                    'name': user.name,
                    'mobile_number': user.mobile_number,
                    'role': user.role,
                },
            },
        )
