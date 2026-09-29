from django.urls import path

from accounts.views import ChangePasswordView, ProfileView

app_name = 'accounts'

urlpatterns = [
    path('', ProfileView.as_view(), name='profile'),
    path('change-password/', ChangePasswordView.as_view(), name='change-password'),
]
