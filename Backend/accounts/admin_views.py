from drf_spectacular.utils import OpenApiParameter, extend_schema
from rest_framework import status

from accounts.admin_serializers import AdminUserDetailSerializer, AdminUserSerializer
from accounts.admin_services import get_admin_user, list_admin_users
from accounts.exceptions import (
    AdminUserError,
    AdminUserNotFound,
    AdminUserPermissionDenied,
    InvalidAdminUserQuery,
)
from core.api import ServiceAPIView
from core.permissions import IsActiveAdmin
from core.utils import success_response


class AdminUserAPIView(ServiceAPIView):
    """Admin-only and read-only; the reading admin is always request.user."""

    permission_classes = [IsActiveAdmin]
    service_error = AdminUserError
    error_status = {
        AdminUserPermissionDenied: status.HTTP_403_FORBIDDEN,
        AdminUserNotFound: status.HTTP_404_NOT_FOUND,
        InvalidAdminUserQuery: status.HTTP_400_BAD_REQUEST,
    }


class AdminUsersView(AdminUserAPIView):
    @extend_schema(
        parameters=[
            OpenApiParameter('is_active', str, enum=['true', 'false']),
            OpenApiParameter('is_verified', str, enum=['true', 'false']),
            OpenApiParameter('search', str, description='Name; digits also match the mobile number.'),
            OpenApiParameter('page', int, description='1-based page number.'),
            OpenApiParameter('page_size', int, description='1-100; default 20.'),
        ],
        responses={200: AdminUserSerializer(many=True)},
    )
    def get(self, request):
        params = request.query_params
        users, meta = list_admin_users(
            admin=request.user,
            is_active=params.get('is_active'),
            is_verified=params.get('is_verified'),
            search=params.get('search'),
            page=params.get('page'),
            page_size=params.get('page_size'),
        )
        return success_response('Users.', data={'users': AdminUserSerializer(users, many=True).data, **meta})


class AdminUserDetailView(AdminUserAPIView):
    @extend_schema(responses={200: AdminUserDetailSerializer})
    def get(self, request, user_id):
        detail = get_admin_user(admin=request.user, user_id=user_id)
        return success_response('User.', data=AdminUserDetailSerializer(detail).data)
