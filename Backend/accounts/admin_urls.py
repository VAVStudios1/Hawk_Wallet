from django.urls import path

from accounts.admin_views import AdminUserDetailView, AdminUsersView

app_name = 'admin-users'

# Mounted under api/admin/users/; admin-only.
urlpatterns = [
    path('', AdminUsersView.as_view(), name='users'),
    path('<int:user_id>/', AdminUserDetailView.as_view(), name='user-detail'),
]
