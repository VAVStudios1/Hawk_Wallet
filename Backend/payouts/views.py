from django.http import FileResponse
from drf_spectacular.types import OpenApiTypes
from drf_spectacular.utils import OpenApiParameter, OpenApiResponse, extend_schema
from rest_framework import status

from core.api import ServiceAPIView, client_ip
from core.permissions import IsActiveAdmin
from core.utils import error_response, success_response
from payouts.exceptions import (
    IdempotencyKeyConflict,
    InvalidCancellationReason,
    InvalidIdempotencyKey,
    InvalidPayoutAmount,
    InvalidPayoutQuery,
    InvalidPayoutUtr,
    OrderNotInPayout,
    OrderNotPayable,
    PayoutAlreadyCancelled,
    PayoutAlreadyPaid,
    PayoutConflict,
    PayoutError,
    PayoutExceedsRemaining,
    PayoutNotCancellable,
    PayoutNotFound,
    PayoutNotPending,
    PayoutOrderNotFound,
    PayoutPermissionDenied,
    PayoutQrNotFound,
    PayoutTotalConflict,
    PayoutUtrAlreadyUsed,
)
from payouts.models import Payout
from payouts.serializers import (
    AdminPayoutDetailSerializer,
    AdminPayoutListSerializer,
    AdminPayoutMethodSerializer,
    CancelPayoutSerializer,
    CreatePayoutSerializer,
    MarkPayoutPaidSerializer,
    PayoutSerializer,
)
from payouts.services import (
    get_admin_payout,
    list_admin_payouts,
    cancel_payout,
    create_payout,
    get_payout_method_for_admin,
    get_payout_qr_for_admin,
    mark_payout_paid,
)

ERROR_STATUS = {
    PayoutPermissionDenied: status.HTTP_403_FORBIDDEN,
    InvalidIdempotencyKey: status.HTTP_400_BAD_REQUEST,
    IdempotencyKeyConflict: status.HTTP_409_CONFLICT,
    InvalidPayoutAmount: status.HTTP_400_BAD_REQUEST,
    PayoutOrderNotFound: status.HTTP_404_NOT_FOUND,
    OrderNotPayable: status.HTTP_409_CONFLICT,
    PayoutExceedsRemaining: status.HTTP_409_CONFLICT,
    PayoutConflict: status.HTTP_409_CONFLICT,
    PayoutNotFound: status.HTTP_404_NOT_FOUND,
    PayoutNotPending: status.HTTP_409_CONFLICT,
    PayoutAlreadyPaid: status.HTTP_409_CONFLICT,
    OrderNotInPayout: status.HTTP_409_CONFLICT,
    InvalidPayoutUtr: status.HTTP_400_BAD_REQUEST,
    PayoutUtrAlreadyUsed: status.HTTP_409_CONFLICT,
    PayoutTotalConflict: status.HTTP_409_CONFLICT,
    InvalidCancellationReason: status.HTTP_400_BAD_REQUEST,
    PayoutNotCancellable: status.HTTP_409_CONFLICT,
    PayoutAlreadyCancelled: status.HTTP_409_CONFLICT,
    PayoutQrNotFound: status.HTTP_404_NOT_FOUND,
    InvalidPayoutQuery: status.HTTP_400_BAD_REQUEST,
}


class AdminPayoutAPIView(ServiceAPIView):
    """Admin-only; the acting admin is always request.user."""

    permission_classes = [IsActiveAdmin]
    service_error = PayoutError
    error_status = ERROR_STATUS


class CreatePayoutView(AdminPayoutAPIView):
    @extend_schema(request=CreatePayoutSerializer, responses={201: PayoutSerializer})
    def post(self, request, order_id):
        serializer = CreatePayoutSerializer(data=request.data)
        if not serializer.is_valid():
            return error_response('Payout details are invalid.', errors=serializer.errors)

        data = serializer.validated_data
        payout = create_payout(
            admin=request.user,
            order_id=order_id,
            amount=data['amount'],
            idempotency_key=data['idempotency_key'],
        )
        return success_response(
            'Payout created.',
            data=PayoutSerializer(payout).data,
            status_code=status.HTTP_201_CREATED,
        )


class MarkPayoutPaidView(AdminPayoutAPIView):
    @extend_schema(request=MarkPayoutPaidSerializer, responses={200: PayoutSerializer})
    def post(self, request, payout_id):
        serializer = MarkPayoutPaidSerializer(data=request.data)
        if not serializer.is_valid():
            return error_response('Payment details are invalid.', errors=serializer.errors)

        data = serializer.validated_data
        payout = mark_payout_paid(
            admin=request.user,
            payout_id=payout_id,
            utr=data['utr'],
            idempotency_key=data['idempotency_key'],
        )
        return success_response('Payout marked paid.', data=PayoutSerializer(payout).data)


class CancelPayoutView(AdminPayoutAPIView):
    @extend_schema(request=CancelPayoutSerializer, responses={200: PayoutSerializer})
    def post(self, request, payout_id):
        serializer = CancelPayoutSerializer(data=request.data)
        if not serializer.is_valid():
            return error_response('Cancellation details are invalid.', errors=serializer.errors)

        data = serializer.validated_data
        payout = cancel_payout(
            admin=request.user,
            payout_id=payout_id,
            cancellation_reason=data['cancellation_reason'],
            idempotency_key=data['idempotency_key'],
        )
        return success_response('Payout cancelled.', data=PayoutSerializer(payout).data)


class PayoutMethodForAdminView(AdminPayoutAPIView):
    @extend_schema(responses={200: AdminPayoutMethodSerializer})
    def get(self, request, payout_id):
        # The service records a PAYOUT_METHOD_VIEWED audit event per call.
        view = get_payout_method_for_admin(
            admin=request.user, payout_id=payout_id, ip_address=client_ip(request)
        )
        return success_response('Payout method.', data=AdminPayoutMethodSerializer(view).data)


class PayoutQrForAdminView(AdminPayoutAPIView):
    @extend_schema(responses={200: OpenApiResponse(OpenApiTypes.BINARY, description='QR image')})
    def get(self, request, payout_id):
        # The service records a PAYOUT_METHOD_QR_VIEWED audit event per call.
        qr = get_payout_qr_for_admin(
            admin=request.user, payout_id=payout_id, ip_address=client_ip(request)
        )
        response = FileResponse(qr.file, content_type=qr.content_type)
        # A member's QR must never sit in a shared or browser cache.
        response['Cache-Control'] = 'no-store'
        return response


class AdminPayoutsView(AdminPayoutAPIView):
    @extend_schema(
        parameters=[
            OpenApiParameter('status', str, enum=list(Payout.Status.values), description='Only this status.'),
            OpenApiParameter('search', str, description='Order reference or member name; digits also match mobile / UTR.'),
            OpenApiParameter('ordering', str, enum=['-created_at', 'created_at']),
            OpenApiParameter('page', int, description='1-based page number.'),
            OpenApiParameter('page_size', int, description='1-100; default 20.'),
        ],
        responses={200: AdminPayoutListSerializer(many=True)},
    )
    def get(self, request):
        params = request.query_params
        payouts, meta = list_admin_payouts(
            admin=request.user,
            status=params.get('status'),
            search=params.get('search'),
            ordering=params.get('ordering'),
            page=params.get('page'),
            page_size=params.get('page_size'),
        )
        return success_response(
            'Payouts.',
            data={'payouts': AdminPayoutListSerializer(payouts, many=True).data, **meta},
        )


class AdminPayoutDetailView(AdminPayoutAPIView):
    @extend_schema(responses={200: AdminPayoutDetailSerializer})
    def get(self, request, payout_id):
        # Audited (PAYOUT_METHOD_VIEWED): the detail reveals the member's UPI ID.
        payout = get_admin_payout(admin=request.user, payout_id=payout_id, ip_address=client_ip(request))
        return success_response('Payout.', data=AdminPayoutDetailSerializer(payout).data)
