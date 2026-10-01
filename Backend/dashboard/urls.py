from django.urls import path

from dashboard.views import AdminDashboardView

app_name = 'admin-dashboard'

# Mounted under api/admin/dashboard/; admin-only.
urlpatterns = [
    path('', AdminDashboardView.as_view(), name='dashboard'),
]
