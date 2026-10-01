from django.urls import path

from accounts.admin_management_views import ActivateAdminView, AdminDetailView, AdminsView, DeactivateAdminView

app_name = 'admin-management'

# Mounted under api/admin/admins/; SUPER_ADMIN only.
urlpatterns = [
    path('', AdminsView.as_view(), name='admins'),
    path('<int:admin_id>/', AdminDetailView.as_view(), name='admin-detail'),
    path('<int:admin_id>/activate/', ActivateAdminView.as_view(), name='admin-activate'),
    path('<int:admin_id>/deactivate/', DeactivateAdminView.as_view(), name='admin-deactivate'),
]
