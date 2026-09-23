from django.contrib.auth import authenticate, get_user_model
from django.db import transaction
from rest_framework import status
from rest_framework.permissions import AllowAny
from rest_framework.throttling import ScopedRateThrottle
from rest_framework.views import APIView
from rest_framework_simplejwt.tokens import RefreshToken

from core.models import OTP
from core.serializers import (
    LoginSerializer,
    RegisterSerializer,
    ResendOTPSerializer,
    VerifyOTPSerializer,
)
from core.services import (
    OTPCooldownError,
    OTPExpiredError,
    OTPInvalidError,
    OTPMaxAttemptsError,
    OTPNotFoundError,
    OTPServiceError,
    generate_and_send_otp,
    verify_otp,
)
from core.utils import error_response, success_response

User = get_user_model()


def _otp_error_response(exc: OTPServiceError):
    if isinstance(exc, OTPCooldownError):
        return error_response(
            str(exc),
            errors={'retry_after_seconds': exc.retry_after_seconds},
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
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
            generate_and_send_otp(user, OTP.Purpose.REGISTRATION)

        return success_response(
            'Registration successful. An OTP has been sent to verify your mobile number.',
            data={'mobile_number': user.mobile_number},
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
        if purpose == OTP.Purpose.LOGIN and not user.is_verified:
            return error_response(
                'Please complete registration verification first.',
                status_code=status.HTTP_403_FORBIDDEN,
            )

        try:
            generate_and_send_otp(user, purpose)
        except OTPCooldownError as exc:
            return _otp_error_response(exc)

        return success_response('A new OTP has been sent.')


class LoginView(APIView):
    permission_classes = [AllowAny]
    throttle_classes = [ScopedRateThrottle]
    throttle_scope = 'otp'

    def post(self, request):
        serializer = LoginSerializer(data=request.data)
        if not serializer.is_valid():
            return error_response('Login failed.', errors=serializer.errors)

        mobile_number = serializer.validated_data['mobile_number']
        password = serializer.validated_data['password']

        # authenticate() also enforces is_active, so a disabled account and a
        # wrong password both fall through to the same generic response.
        user = authenticate(request, mobile_number=mobile_number, password=password)
        if user is None:
            return error_response(
                'Invalid mobile number or password.',
                status_code=status.HTTP_401_UNAUTHORIZED,
            )

        if not user.is_verified:
            try:
                generate_and_send_otp(user, OTP.Purpose.REGISTRATION)
            except OTPCooldownError:
                pass
            return error_response(
                'Please verify your mobile number to complete registration. '
                'A new OTP has been sent.',
                status_code=status.HTTP_403_FORBIDDEN,
            )

        try:
            generate_and_send_otp(user, OTP.Purpose.LOGIN)
        except OTPCooldownError as exc:
            return _otp_error_response(exc)

        return success_response(
            'OTP sent. Please verify to complete login.',
            data={'mobile_number': user.mobile_number},
        )


class VerifyLoginOTPView(APIView):
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
