from drf_spectacular.utils import OpenApiParameter, extend_schema
from rest_framework import status

from core.api import ServiceAPIView
from core.permissions import IsActiveAdmin, IsSuperAdmin
from core.utils import success_response
from dashboard.exceptions import (
    DashboardError,
    DashboardPermissionDenied,
    InvalidReportQuery,
    SystemReportPermissionDenied,
)
from dashboard.reports import RANGES, get_report, get_system_report
from dashboard.serializers import AdminDashboardSerializer, AdminReportSerializer, SystemReportSerializer
from dashboard.services import get_admin_dashboard


class AdminDashboardView(ServiceAPIView):
    """Admin-only and read-only; counts are scoped to request.user where the existing rules scope them."""

    permission_classes = [IsActiveAdmin]
    service_error = DashboardError
    error_status = {DashboardPermissionDenied: status.HTTP_403_FORBIDDEN}

    @extend_schema(responses={200: AdminDashboardSerializer})
    def get(self, request):
        dashboard = get_admin_dashboard(admin=request.user)
        return success_response('Dashboard.', data=AdminDashboardSerializer(dashboard).data)


class AdminReportView(ServiceAPIView):
    """ADMIN and SUPER_ADMIN; platform-wide like every admin read."""

    permission_classes = [IsActiveAdmin]
    service_error = DashboardError
    error_status = {
        DashboardPermissionDenied: status.HTTP_403_FORBIDDEN,
        InvalidReportQuery: status.HTTP_400_BAD_REQUEST,
    }

    @extend_schema(
        parameters=[
            OpenApiParameter('range', str, enum=list(RANGES), description='Default today.'),
            OpenApiParameter('start_date', str, description='YYYY-MM-DD; range=custom only.'),
            OpenApiParameter('end_date', str, description='YYYY-MM-DD; range=custom only.'),
        ],
        responses={200: AdminReportSerializer},
    )
    def get(self, request):
        params = request.query_params
        report = get_report(
            admin=request.user,
            range_value=params.get('range'),
            start_date=params.get('start_date'),
            end_date=params.get('end_date'),
        )
        return success_response('Report.', data=AdminReportSerializer(report).data)


class SystemReportView(ServiceAPIView):
    """SUPER_ADMIN only."""

    permission_classes = [IsSuperAdmin]
    service_error = DashboardError
    error_status = {SystemReportPermissionDenied: status.HTTP_403_FORBIDDEN}

    @extend_schema(responses={200: SystemReportSerializer})
    def get(self, request):
        report = get_system_report(admin=request.user)
        return success_response('System report.', data=SystemReportSerializer(report).data)
