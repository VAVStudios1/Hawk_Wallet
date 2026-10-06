from drf_spectacular.utils import OpenApiParameter, extend_schema
from rest_framework import status

from core.api import ServiceAPIView
from core.permissions import IsVerifiedMember
from core.utils import success_response
from home.exceptions import HomeError, HomePermissionDenied, InvalidTransactionQuery
from home.serializers import MemberHomeSerializer, TransactionHistoryItemSerializer
from home.services import HISTORY_RANGES, HISTORY_TYPES, get_member_home, list_member_transactions


class MemberHomeView(ServiceAPIView):
    """Member-only; always the dashboard of request.user."""

    permission_classes = [IsVerifiedMember]
    service_error = HomeError
    error_status = {HomePermissionDenied: status.HTTP_403_FORBIDDEN}

    @extend_schema(responses={200: MemberHomeSerializer})
    def get(self, request):
        home = get_member_home(user=request.user)
        return success_response('Home.', data=MemberHomeSerializer(home).data)


class MemberTransactionsView(ServiceAPIView):
    """Member-only and read-only; always request.user's own pay-ins and payouts."""

    permission_classes = [IsVerifiedMember]
    service_error = HomeError
    error_status = {
        HomePermissionDenied: status.HTTP_403_FORBIDDEN,
        InvalidTransactionQuery: status.HTTP_400_BAD_REQUEST,
    }

    @extend_schema(
        parameters=[
            OpenApiParameter('range', str, enum=list(HISTORY_RANGES), description='Default 7d.'),
            OpenApiParameter('start_date', str, description='YYYY-MM-DD, inclusive; range=custom only.'),
            OpenApiParameter('end_date', str, description='YYYY-MM-DD, inclusive; range=custom only.'),
            OpenApiParameter(
                'type', str, enum=list(HISTORY_TYPES),
                description='all (default) = pay-ins and payouts; buy = pay-ins only; sell = payouts only.',
            ),
            OpenApiParameter('page', int, description='1-based page number.'),
            OpenApiParameter('page_size', int, description='1-100; default 20.'),
        ],
        responses={200: TransactionHistoryItemSerializer(many=True)},
    )
    def get(self, request):
        params = request.query_params
        period, type_value, items, meta = list_member_transactions(
            user=request.user,
            range_value=params.get('range'),
            start_date=params.get('start_date'),
            end_date=params.get('end_date'),
            type_value=params.get('type'),
            page=params.get('page'),
            page_size=params.get('page_size'),
        )
        return success_response(
            'Transactions.',
            data={
                'transactions': TransactionHistoryItemSerializer(items, many=True).data,
                # The resolved period, so clients show the server's dates.
                'range': period.range,
                'start_date': period.start_date.isoformat(),
                'end_date': period.end_date.isoformat(),
                'type': type_value,
                **meta,
            },
        )
