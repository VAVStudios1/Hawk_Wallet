from drf_spectacular.utils import extend_schema
from rest_framework import status

from core.api import ServiceAPIView, client_ip
from core.permissions import IsVerifiedMember
from core.utils import error_response, success_response
from paymethods.exceptions import (
    DuplicatePayoutMethod,
    InvalidPayoutMethod,
    PayoutMethodConflict,
    PayoutMethodError,
    PayoutMethodInactive,
    PayoutMethodNotFound,
    PayoutMethodPermissionDenied,
)
from paymethods.serializers import (
    AddPayoutMethodSerializer,
    PayoutMethodListItemSerializer,
    PayoutMethodSerializer,
)
from paymethods.services import (
    add_payout_method,
    deactivate_payout_method,
    list_payout_methods,
    set_default_payout_method,
)

ERROR_STATUS = {
    PayoutMethodPermissionDenied: status.HTTP_403_FORBIDDEN,
    # Also raised for another member's method: same 404 either way.
    PayoutMethodNotFound: status.HTTP_404_NOT_FOUND,
    InvalidPayoutMethod: status.HTTP_400_BAD_REQUEST,
    DuplicatePayoutMethod: status.HTTP_409_CONFLICT,
    PayoutMethodInactive: status.HTTP_409_CONFLICT,
    PayoutMethodConflict: status.HTTP_409_CONFLICT,
}


class PayoutMethodAPIView(ServiceAPIView):
    """Member-only; the acting user is always request.user."""

    permission_classes = [IsVerifiedMember]
    service_error = PayoutMethodError
    error_status = ERROR_STATUS


def _method_data(method):
    return PayoutMethodSerializer(method).data


class PayoutMethodsView(PayoutMethodAPIView):
    @extend_schema(responses={200: PayoutMethodListItemSerializer(many=True)})
    def get(self, request):
        # Read-only: always request.user's own methods, never another member's.
        methods = list_payout_methods(user=request.user)
        return success_response(
            'Payout methods.',
            data={'methods': PayoutMethodListItemSerializer(methods, many=True).data},
        )

    @extend_schema(request=AddPayoutMethodSerializer, responses={201: PayoutMethodSerializer})
    def post(self, request):
        serializer = AddPayoutMethodSerializer(data=request.data)
        if not serializer.is_valid():
            return error_response('Payout method details are invalid.', errors=serializer.errors)

        data = serializer.validated_data
        method = add_payout_method(
            user=request.user,
            provider_id=data['provider'],
            upi_id=data['upi_id'],
            qr_image=data['qr_image'],
            label=data['label'],
            ip_address=client_ip(request),
        )
        return success_response(
            'Payout method added.',
            data=_method_data(method),
            status_code=status.HTTP_201_CREATED,
        )


class SetDefaultPayoutMethodView(PayoutMethodAPIView):
    @extend_schema(request=None, responses={200: PayoutMethodSerializer})
    def post(self, request, method_id):
        method = set_default_payout_method(
            user=request.user, method_id=method_id, ip_address=client_ip(request)
        )
        return success_response('Default payout method updated.', data=_method_data(method))


class DeactivatePayoutMethodView(PayoutMethodAPIView):
    @extend_schema(request=None, responses={200: PayoutMethodSerializer})
    def post(self, request, method_id):
        method = deactivate_payout_method(
            user=request.user, method_id=method_id, ip_address=client_ip(request)
        )
        return success_response('Payout method deactivated.', data=_method_data(method))
