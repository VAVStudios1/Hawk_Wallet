from drf_spectacular.utils import OpenApiParameter, extend_schema
from rest_framework import status

from core.api import ServiceAPIView, client_ip
from core.permissions import IsActiveAdmin, IsVerifiedMember
from core.utils import error_response, success_response
from payins.exceptions import (
    BuyMethodRequired,
    BuyMethodUnavailable,
    IdempotencyKeyConflict,
    InvalidIdempotencyKey,
    InvalidPayInIntent,
    InvalidPayInQuery,
    InvalidRejectionReason,
    InvalidUtr,
    NoReceivingAccount,
    OrderNotPayable,
    PayerAppUnavailable,
    PayInAlreadyReviewed,
    PayInConflict,
    PayInError,
    PayInNotFound,
    PayInOrderNotFound,
    PayInPermissionDenied,
    ReviewPermissionDenied,
    UtrAlreadySubmitted,
)
from payins.models import PayIn
from payins.serializers import (
    AdminPayInDetailSerializer,
    AdminPayInListSerializer,
    AdminPayInSerializer,
    PayInIntentSerializer,
    PayInSerializer,
    RejectPayInSerializer,
    ReviewPayInSerializer,
    SubmitPayInSerializer,
)
from payins.services import (
    confirm_pay_in,
    get_admin_pay_in,
    get_pay_in_intent,
    list_admin_pay_ins,
    reject_pay_in,
    submit_pay_in_utr,
)

ERROR_STATUS = {
    PayInPermissionDenied: status.HTTP_403_FORBIDDEN,
    # Also raised for another member's order: same 404 either way.
    PayInOrderNotFound: status.HTTP_404_NOT_FOUND,
    OrderNotPayable: status.HTTP_409_CONFLICT,
    NoReceivingAccount: status.HTTP_503_SERVICE_UNAVAILABLE,
    InvalidPayInIntent: status.HTTP_400_BAD_REQUEST,
    InvalidUtr: status.HTTP_400_BAD_REQUEST,
    InvalidIdempotencyKey: status.HTTP_400_BAD_REQUEST,
    IdempotencyKeyConflict: status.HTTP_409_CONFLICT,
    UtrAlreadySubmitted: status.HTTP_409_CONFLICT,
    PayerAppUnavailable: status.HTTP_400_BAD_REQUEST,
    BuyMethodRequired: status.HTTP_400_BAD_REQUEST,
    BuyMethodUnavailable: status.HTTP_400_BAD_REQUEST,
    PayInConflict: status.HTTP_409_CONFLICT,
    ReviewPermissionDenied: status.HTTP_403_FORBIDDEN,
    PayInNotFound: status.HTTP_404_NOT_FOUND,
    PayInAlreadyReviewed: status.HTTP_409_CONFLICT,
    InvalidRejectionReason: status.HTTP_400_BAD_REQUEST,
    InvalidPayInQuery: status.HTTP_400_BAD_REQUEST,
}


class MemberPayInAPIView(ServiceAPIView):
    """Member-only; the order is always looked up as request.user's own."""

    permission_classes = [IsVerifiedMember]
    service_error = PayInError
    error_status = ERROR_STATUS


class PayInIntentView(MemberPayInAPIView):
    @extend_schema(responses={200: PayInIntentSerializer})
    def get(self, request, order_id):
        # Read-only: builds the intent, writes nothing.
        intent = get_pay_in_intent(user=request.user, order_id=order_id)
        return success_response('Pay-in intent ready.', data=PayInIntentSerializer(intent).data)


class SubmitPayInView(MemberPayInAPIView):
    @extend_schema(request=SubmitPayInSerializer, responses={201: PayInSerializer})
    def post(self, request, order_id):
        serializer = SubmitPayInSerializer(data=request.data)
        if not serializer.is_valid():
            return error_response('Payment details are invalid.', errors=serializer.errors)

        data = serializer.validated_data
        payin = submit_pay_in_utr(
            user=request.user,
            order_id=order_id,
            utr=data['utr'],
            idempotency_key=data['idempotency_key'],
            intent_token=data['intent_token'],
            buy_method_id=data.get('buy_method_id'),
            payer_app=data.get('payer_app'),
        )
        return success_response(
            'Payment submitted for review.',
            data=PayInSerializer(payin).data,
            status_code=status.HTTP_201_CREATED,
        )


class AdminPayInAPIView(ServiceAPIView):
    """Admin-only; the reviewer is always request.user."""

    permission_classes = [IsActiveAdmin]
    service_error = PayInError
    error_status = ERROR_STATUS


class ConfirmPayInView(AdminPayInAPIView):
    @extend_schema(request=ReviewPayInSerializer, responses={200: AdminPayInSerializer})
    def post(self, request, payin_id):
        serializer = ReviewPayInSerializer(data=request.data)
        if not serializer.is_valid():
            return error_response('Review details are invalid.', errors=serializer.errors)

        payin = confirm_pay_in(
            admin=request.user,
            payin_id=payin_id,
            idempotency_key=serializer.validated_data['idempotency_key'],
        )
        return success_response('Pay-in confirmed.', data=AdminPayInSerializer(payin).data)


class RejectPayInView(AdminPayInAPIView):
    @extend_schema(request=RejectPayInSerializer, responses={200: AdminPayInSerializer})
    def post(self, request, payin_id):
        serializer = RejectPayInSerializer(data=request.data)
        if not serializer.is_valid():
            return error_response('Review details are invalid.', errors=serializer.errors)

        data = serializer.validated_data
        payin = reject_pay_in(
            admin=request.user,
            payin_id=payin_id,
            rejection_reason=data['rejection_reason'],
            idempotency_key=data['idempotency_key'],
        )
        return success_response('Pay-in rejected.', data=AdminPayInSerializer(payin).data)


class AdminPayInsView(AdminPayInAPIView):
    @extend_schema(
        parameters=[
            OpenApiParameter('status', str, enum=list(PayIn.Status.values), description='Only this status.'),
            OpenApiParameter('search', str, description='Order reference or member name; digits also match mobile / UTR.'),
            OpenApiParameter('ordering', str, enum=['-submitted_at', 'submitted_at']),
            OpenApiParameter('page', int, description='1-based page number.'),
            OpenApiParameter('page_size', int, description='1-100; default 20.'),
        ],
        responses={200: AdminPayInListSerializer(many=True)},
    )
    def get(self, request):
        params = request.query_params
        pay_ins, meta = list_admin_pay_ins(
            admin=request.user,
            status=params.get('status'),
            search=params.get('search'),
            ordering=params.get('ordering'),
            page=params.get('page'),
            page_size=params.get('page_size'),
        )
        return success_response(
            'Pay-ins.',
            data={'pay_ins': AdminPayInListSerializer(pay_ins, many=True).data, **meta},
        )


class AdminPayInDetailView(AdminPayInAPIView):
    @extend_schema(responses={200: AdminPayInDetailSerializer})
    def get(self, request, payin_id):
        # Audited (BUY_METHOD_VIEWED) when the pay-in names the member's BUY account.
        payin = get_admin_pay_in(admin=request.user, payin_id=payin_id, ip_address=client_ip(request))
        return success_response('Pay-in.', data=AdminPayInDetailSerializer(payin).data)
