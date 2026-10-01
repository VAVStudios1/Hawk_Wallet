from django.urls import path

from audit.admin_views import AuditEventDetailView, AuditEventsView

app_name = 'admin-audit'

# Mounted under api/admin/audit-events/; SUPER_ADMIN only.
urlpatterns = [
    path('', AuditEventsView.as_view(), name='events'),
    path('<int:event_id>/', AuditEventDetailView.as_view(), name='event-detail'),
]
