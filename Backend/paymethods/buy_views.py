from drf_spectacular.utils import extend_schema
from rest_framework import status

from core.api import ServiceAPIView, client_ip
from core.permissions import IsVerifiedMember
from core.utils import error_response, success_response
from paymethods.buy_services import (
    add_buy_method,
    deactivate_buy_method,
    list_buy_methods,
    resolve_buy_provider_id,
    set_default_buy_method,
)
from paymethods.exceptions import (
    BuyMethodConflict,
    BuyMethodError,
    BuyMethodInactive,
    BuyMethodNotFound,
    BuyMethodPermissionDenied,
    DuplicateBuyMethod,
    InvalidBuyMethod,
)
from paymethods.serializers import (
    AddBuyMethodSerializer,
    BuyMethodListItemSerializer,
    BuyMethodSerializer,
)

ERROR_STATUS = {
    BuyMethodPermissionDenied: status.HTTP_403_FORBIDDEN,
    # Also raised for another member's method: same 404 either way.
    BuyMethodNotFound: status.HTTP_404_NOT_FOUND,
    InvalidBuyMethod: status.HTTP_400_BAD_REQUEST,
    DuplicateBuyMethod: status.HTTP_409_CONFLICT,
    BuyMethodInactive: status.HTTP_409_CONFLICT,
    BuyMethodConflict: status.HTTP_409_CONFLICT,
}


class BuyMethodAPIView(ServiceAPIView):
    """Member-only; the acting user is always request.user."""

    permission_classes = [IsVerifiedMember]
    service_error = BuyMethodError
    error_status = ERROR_STATUS


def _method_data(method):
    return BuyMethodSerializer(method).data


class BuyMethodsView(BuyMethodAPIView):
    @extend_schema(responses={200: BuyMethodListItemSerializer(many=True)})
    def get(self, request):
        # Read-only: always request.user's own methods, never another member's.
        methods = list_buy_methods(user=request.user)
        return success_response(
            'Buy methods.',
            data={'methods': BuyMethodListItemSerializer(methods, many=True).data},
        )

    @extend_schema(request=AddBuyMethodSerializer, responses={201: BuyMethodSerializer})
    def post(self, request):
        serializer = AddBuyMethodSerializer(data=request.data)
        if not serializer.is_valid():
            return error_response('Buy method details are invalid.', errors=serializer.errors)

        data = serializer.validated_data
        provider_id = resolve_buy_provider_id(
            provider_id=data.get('provider'),
            provider_code=data.get('provider_code'),
        )
        method = add_buy_method(
            user=request.user,
            provider_id=provider_id,
            upi_id=data['upi_id'],
            label=data['label'],
            ip_address=client_ip(request),
        )
        return success_response(
            'Buy method added.',
            data=_method_data(method),
            status_code=status.HTTP_201_CREATED,
        )


class SetDefaultBuyMethodView(BuyMethodAPIView):
    @extend_schema(request=None, responses={200: BuyMethodSerializer})
    def post(self, request, method_id):
        method = set_default_buy_method(
            user=request.user, method_id=method_id, ip_address=client_ip(request)
        )
        return success_response('Default buy method updated.', data=_method_data(method))


class DeactivateBuyMethodView(BuyMethodAPIView):
    @extend_schema(request=None, responses={200: BuyMethodSerializer})
    def post(self, request, method_id):
        method = deactivate_buy_method(
            user=request.user, method_id=method_id, ip_address=client_ip(request)
        )
        return success_response('Buy method deactivated.', data=_method_data(method))
