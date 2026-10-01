from drf_spectacular.types import OpenApiTypes
from drf_spectacular.utils import OpenApiParameter, extend_schema
from rest_framework import status

from core.api import ServiceAPIView, client_ip
from core.permissions import IsActiveAdmin, IsVerifiedMember
from core.utils import error_response, success_response
from orders.exceptions import (
    AdminOrderReadPermissionDenied,
    InvalidAdminOrderQuery,
    ClaimConflict,
    ClaimError,
    ClaimPermissionDenied,
    IdempotencyKeyConflict,
    InvalidIdempotencyKey,
    InvalidOffer,
    InvalidOfferFilter,
    InvalidOfferIdempotencyKey,
    NoActivePayoutMethod,
    OfferCreateConflict,
    OfferCreateError,
    OfferCreatePermissionDenied,
    OfferIdempotencyKeyConflict,
    OfferNotFound,
    OfferSoldOut,
    OfferUnavailable,
    OrderNotFound,
    OrderReadError,
    OrderReadPermissionDenied,
    PayoutMethodUnavailable,
)
from orders.models import Order
from orders.serializers import (
    AdminOfferSerializer,
    AdminOrderDetailSerializer,
    AdminOrderSerializer,
    ClaimOrderSerializer,
    CreateOfferSerializer,
    OfferPreviewSerializer,
    OrderDetailSerializer,
    OrderSerializer,
)
from orders.services import (
    claim_order,
    create_offer,
    get_admin_order,
    get_member_order,
    list_admin_orders,
    list_claimable_offers,
    list_member_orders,
)

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


READ_ERROR_STATUS = {
    OrderReadPermissionDenied: status.HTTP_403_FORBIDDEN,
    # Also raised for another member's order: same 404 either way.
    OrderNotFound: status.HTTP_404_NOT_FOUND,
    InvalidOfferFilter: status.HTTP_400_BAD_REQUEST,
}


class MemberOrderReadAPIView(ServiceAPIView):
    """Member-only and read-only; always request.user's own orders."""

    permission_classes = [IsVerifiedMember]
    service_error = OrderReadError
    error_status = READ_ERROR_STATUS


OFFER_AMOUNT_FILTER_PARAMETERS = [
    OpenApiParameter(
        name,
        OpenApiTypes.STR,
        OpenApiParameter.QUERY,
        required=False,
        pattern=r'^[0-9]{1,10}$',
        description=(
            f'Optional. Only offers with order_amount {op} this value (inclusive). '
            'Whole rupees: ASCII digits only, 0-9999999999; no sign, decimals or '
            'blank value (400 otherwise, and when min_amount > max_amount).'
        ),
    )
    for name, op in (('min_amount', '>='), ('max_amount', '<='))
]


class ClaimableOffersView(MemberOrderReadAPIView):
    @extend_schema(
        parameters=OFFER_AMOUNT_FILTER_PARAMETERS,
        responses={200: OfferPreviewSerializer(many=True)},
    )
    def get(self, request):
        offers = list_claimable_offers(
            user=request.user,
            min_amount=request.query_params.get('min_amount'),
            max_amount=request.query_params.get('max_amount'),
        )
        return success_response(
            'Available offers.',
            data={'offers': OfferPreviewSerializer(offers, many=True).data},
        )


class MemberOrdersView(MemberOrderReadAPIView):
    @extend_schema(responses={200: OrderSerializer(many=True)})
    def get(self, request):
        orders = list_member_orders(user=request.user)
        return success_response(
            'Orders.',
            data={'orders': OrderSerializer(orders, many=True).data},
        )


class MemberOrderDetailView(MemberOrderReadAPIView):
    @extend_schema(responses={200: OrderDetailSerializer})
    def get(self, request, order_id):
        order = get_member_order(user=request.user, order_id=order_id)
        return success_response('Order.', data=OrderDetailSerializer(order).data)


ADMIN_READ_ERROR_STATUS = {
    AdminOrderReadPermissionDenied: status.HTTP_403_FORBIDDEN,
    OrderNotFound: status.HTTP_404_NOT_FOUND,
    InvalidAdminOrderQuery: status.HTTP_400_BAD_REQUEST,
}


class AdminOrderReadAPIView(ServiceAPIView):
    """Admin-only and read-only; the reading admin is always request.user."""

    permission_classes = [IsActiveAdmin]
    service_error = OrderReadError
    error_status = ADMIN_READ_ERROR_STATUS


class AdminOrdersView(AdminOrderReadAPIView):
    @extend_schema(
        parameters=[
            OpenApiParameter('status', str, enum=list(Order.Status.values), description='Only this status.'),
            OpenApiParameter('awaiting_action', str, enum=['true', 'false'],
                             description='true: only orders an admin has a pending step on.'),
            OpenApiParameter('search', str, description='Reference or member name; digits also match mobile.'),
            OpenApiParameter('page', int, description='1-based page number.'),
            OpenApiParameter('page_size', int, description='1-100; default 20.'),
        ],
        responses={200: AdminOrderSerializer(many=True)},
    )
    def get(self, request):
        params = request.query_params
        orders, meta = list_admin_orders(
            admin=request.user,
            status=params.get('status'),
            awaiting_action=params.get('awaiting_action'),
            search=params.get('search'),
            page=params.get('page'),
            page_size=params.get('page_size'),
        )
        return success_response(
            'Orders.',
            data={'orders': AdminOrderSerializer(orders, many=True).data, **meta},
        )


class AdminOrderDetailView(AdminOrderReadAPIView):
    @extend_schema(responses={200: AdminOrderDetailSerializer})
    def get(self, request, order_id):
        # The service records a PAYOUT_METHOD_VIEWED audit event per call.
        order = get_admin_order(
            admin=request.user, order_id=order_id, ip_address=client_ip(request)
        )
        return success_response('Order.', data=AdminOrderDetailSerializer(order).data)


OFFER_CREATE_ERROR_STATUS = {
    OfferCreatePermissionDenied: status.HTTP_403_FORBIDDEN,
    InvalidOfferIdempotencyKey: status.HTTP_400_BAD_REQUEST,
    OfferIdempotencyKeyConflict: status.HTTP_409_CONFLICT,
    InvalidOffer: status.HTTP_400_BAD_REQUEST,
    OfferCreateConflict: status.HTTP_409_CONFLICT,
}


class AdminCreateOfferView(ServiceAPIView):
    """Admin-only; the creating admin is always request.user."""

    permission_classes = [IsActiveAdmin]
    service_error = OfferCreateError
    error_status = OFFER_CREATE_ERROR_STATUS

    @extend_schema(request=CreateOfferSerializer, responses={201: AdminOfferSerializer})
    def post(self, request):
        serializer = CreateOfferSerializer(data=request.data)
        if not serializer.is_valid():
            return error_response('Offer details are invalid.', errors=serializer.errors)

        data = serializer.validated_data
        offer = create_offer(
            admin=request.user,
            order_amount=data['order_amount'],
            income_rate=data['income_rate'],
            quantity_available=data.get('quantity_available'),
            is_active=data['is_active'],
            pay_in_qr=data['pay_in_qr'],
            idempotency_key=data['idempotency_key'],
            ip_address=client_ip(request),
        )
        return success_response(
            'Offer created.',
            data=AdminOfferSerializer(offer).data,
            status_code=status.HTTP_201_CREATED,
        )
