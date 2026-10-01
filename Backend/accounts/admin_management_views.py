from drf_spectacular.utils import OpenApiParameter, extend_schema
from rest_framework import status

from accounts import admin_management
from accounts.admin_serializers import AdminAccountSerializer, CreateAdminSerializer
from accounts.exceptions import (
    AdminAccountNotFound,
    AdminManagementError,
    AdminManagementPermissionDenied,
    AdminNotManageable,
    CannotManageOwnAccount,
    InvalidAdminQuery,
    MobileNumberTaken,
)
from core.api import ServiceAPIView, client_ip
from core.permissions import IsSuperAdmin
from core.utils import error_response, success_response


class AdminManagementAPIView(ServiceAPIView):
    """SUPER_ADMIN-only; the acting super admin is always request.user."""

    permission_classes = [IsSuperAdmin]
    service_error = AdminManagementError
    error_status = {
        AdminManagementPermissionDenied: status.HTTP_403_FORBIDDEN,
        AdminAccountNotFound: status.HTTP_404_NOT_FOUND,
        InvalidAdminQuery: status.HTTP_400_BAD_REQUEST,
        MobileNumberTaken: status.HTTP_409_CONFLICT,
        CannotManageOwnAccount: status.HTTP_409_CONFLICT,
        AdminNotManageable: status.HTTP_409_CONFLICT,
    }


class AdminsView(AdminManagementAPIView):
    @extend_schema(
        parameters=[
            OpenApiParameter('is_active', str, enum=['true', 'false']),
            OpenApiParameter('search', str, description='Name; digits also match the mobile number.'),
            OpenApiParameter('page', int, description='1-based page number.'),
            OpenApiParameter('page_size', int, description='1-100; default 20.'),
        ],
        responses={200: AdminAccountSerializer(many=True)},
    )
    def get(self, request):
        params = request.query_params
        admins, meta = admin_management.list_admins(
            admin=request.user,
            is_active=params.get('is_active'),
            search=params.get('search'),
            page=params.get('page'),
            page_size=params.get('page_size'),
        )
        data = AdminAccountSerializer(admins, many=True, context={'request': request}).data
        return success_response('Admins.', data={'admins': data, **meta})

    @extend_schema(request=CreateAdminSerializer, responses={201: AdminAccountSerializer})
    def post(self, request):
        serializer = CreateAdminSerializer(data=request.data)
        if not serializer.is_valid():
            return error_response('Admin details are invalid.', errors=serializer.errors)
        data = serializer.validated_data
        account = admin_management.create_admin(
            admin=request.user,
            name=data['name'],
            mobile_number=data['mobile_number'],
            password=data['password'],
            ip_address=client_ip(request),
        )
        return success_response(
            'Admin created. They verify their mobile number with an OTP on first login.',
            data=AdminAccountSerializer(account, context={'request': request}).data,
            status_code=status.HTTP_201_CREATED,
        )


class AdminDetailView(AdminManagementAPIView):
    @extend_schema(responses={200: AdminAccountSerializer})
    def get(self, request, admin_id):
        account = admin_management.get_admin(admin=request.user, admin_id=admin_id)
        return success_response('Admin.', data=AdminAccountSerializer(account, context={'request': request}).data)


class _AdminStatusView(AdminManagementAPIView):
    is_active = None
    message = ''

    @extend_schema(request=None, responses={200: AdminAccountSerializer})
    def post(self, request, admin_id):
        account = admin_management.set_admin_active(
            admin=request.user, admin_id=admin_id, is_active=self.is_active, ip_address=client_ip(request)
        )
        return success_response(self.message, data=AdminAccountSerializer(account, context={'request': request}).data)


class ActivateAdminView(_AdminStatusView):
    is_active = True
    message = 'Admin activated.'


class DeactivateAdminView(_AdminStatusView):
    is_active = False
    message = 'Admin deactivated.'
