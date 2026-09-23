from django.urls import path

from core.views import (
    LoginView,
    RegisterView,
    ResendOTPView,
    VerifyLoginOTPView,
    VerifyRegistrationOTPView,
)

app_name = 'core'

urlpatterns = [
    path('register/', RegisterView.as_view(), name='register'),
    path('verify-otp/', VerifyRegistrationOTPView.as_view(), name='verify-otp'),
    path('resend-otp/', ResendOTPView.as_view(), name='resend-otp'),
    path('login/', LoginView.as_view(), name='login'),
    path('verify-login-otp/', VerifyLoginOTPView.as_view(), name='verify-login-otp'),
]
