from django.urls import path

from dashboard.views import AdminReportView, SystemReportView

app_name = 'admin-reports'

# Mounted under api/admin/. reports/: ADMIN and SUPER_ADMIN; system-report/: SUPER_ADMIN only.
urlpatterns = [
    path('reports/', AdminReportView.as_view(), name='reports'),
    path('system-report/', SystemReportView.as_view(), name='system-report'),
]
