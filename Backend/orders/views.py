from drf_spectacular.utils import extend_schema
from rest_framework import status

from core.api import ServiceAPIView
from core.permissions import IsVerifiedMember
from core.utils import error_response, success_response
from orders.exceptions import (
    ClaimConflict,
    ClaimError,
    ClaimPermissionDenied,
    IdempotencyKeyConflict,
    InvalidIdempotencyKey,
    NoActivePayoutMethod,
    OfferNotFound,
    OfferSoldOut,
    OfferUnavailable,
    PayoutMethodUnavailable,
)
from orders.serializers import ClaimOrderSerializer, OrderSerializer
from orders.services import claim_order

ERROR_STATUS = {
    ClaimPermissionDenied: status.HTTP_403_FORBIDDEN,
    InvalidIdempotencyKey: status.HTTP_400_BAD_REQUEST,
    IdempotencyKeyConflict: status.HTTP_409_CONFLICT,
    OfferNotFound: status.HTTP_404_NOT_FOUND,
    OfferUnavailable: status.HTTP_400_BAD_REQUEST,
    OfferSoldOut: status.HTTP_409_CONFLICT,
    NoActivePayoutMethod: status.HTTP_400_BAD_REQUEST,
    # Also covers another member's method, so it is never revealed.
    PayoutMethodUnavailable: status.HTTP_400_BAD_REQUEST,
    ClaimConflict: status.HTTP_409_CONFLICT,
}


class ClaimOrderView(ServiceAPIView):
    """Member-only; the order always belongs to request.user."""

    permission_classes = [IsVerifiedMember]
    service_error = ClaimError
    error_status = ERROR_STATUS

    @extend_schema(request=ClaimOrderSerializer, responses={201: OrderSerializer})
    def post(self, request):
        serializer = ClaimOrderSerializer(data=request.data)
        if not serializer.is_valid():
            return error_response('Claim details are invalid.', errors=serializer.errors)

        data = serializer.validated_data
        order = claim_order(
            user=request.user,
            offer_id=data['offer_id'],
            idempotency_key=data['idempotency_key'],
            payout_method_id=data.get('payout_method_id'),
        )
        return success_response(
            'Order claimed.',
            data=OrderSerializer(order).data,
            status_code=status.HTTP_201_CREATED,
        )
