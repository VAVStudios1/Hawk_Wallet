from drf_spectacular.utils import OpenApiParameter, extend_schema
from rest_framework import status

from core.api import ServiceAPIView
from core.permissions import IsVerifiedMember
from core.utils import success_response
from team.exceptions import InvalidPage, InvalidTeamLevel, TeamError, TeamPermissionDenied
from team.serializers import (
    CommissionSerializer,
    ReferralInfoSerializer,
    TeamMemberSerializer,
    TeamSummarySerializer,
)
from team.services import (
    get_referral_info,
    get_team_summary,
    list_commissions,
    list_team_members,
    paginate,
)

ERROR_STATUS = {
    TeamPermissionDenied: status.HTTP_403_FORBIDDEN,
    InvalidTeamLevel: status.HTTP_400_BAD_REQUEST,
    InvalidPage: status.HTTP_400_BAD_REQUEST,
}

PAGE_PARAMETERS = [
    OpenApiParameter('page', int, description='1-based page number (default 1).'),
    OpenApiParameter('page_size', int, description='Items per page, 1-100 (default 50).'),
]


class TeamReadAPIView(ServiceAPIView):
    """Member-only and read-only; always request.user's own team."""

    permission_classes = [IsVerifiedMember]
    service_error = TeamError
    error_status = ERROR_STATUS


class ReferralInfoView(TeamReadAPIView):
    @extend_schema(responses={200: ReferralInfoSerializer})
    def get(self, request):
        info = get_referral_info(user=request.user)
        return success_response('Referral information.', data=ReferralInfoSerializer(info).data)


class TeamSummaryView(TeamReadAPIView):
    @extend_schema(responses={200: TeamSummarySerializer})
    def get(self, request):
        summary = get_team_summary(user=request.user)
        return success_response('Team summary.', data=TeamSummarySerializer(summary).data)


class TeamMembersView(TeamReadAPIView):
    @extend_schema(
        parameters=[
            OpenApiParameter('level', int, description='Only this level (1 or more). Default: every level.'),
            *PAGE_PARAMETERS,
        ],
        responses={200: TeamMemberSerializer(many=True)},
    )
    def get(self, request):
        members = list_team_members(user=request.user, level=request.query_params.get('level'))
        items, meta = paginate(
            members, request.query_params.get('page'), request.query_params.get('page_size')
        )
        return success_response(
            'Team members.',
            data={'members': TeamMemberSerializer(items, many=True).data, **meta},
        )


class CommissionHistoryView(TeamReadAPIView):
    @extend_schema(parameters=PAGE_PARAMETERS, responses={200: CommissionSerializer(many=True)})
    def get(self, request):
        commissions = list_commissions(user=request.user)
        items, meta = paginate(
            commissions, request.query_params.get('page'), request.query_params.get('page_size')
        )
        return success_response(
            'Commission history.',
            data={'commissions': CommissionSerializer(items, many=True).data, **meta},
        )
