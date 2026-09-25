from drf_spectacular.utils import extend_schema
from rest_framework import status

from core.api import ServiceAPIView
from core.permissions import IsVerifiedMember
from core.utils import success_response
from home.exceptions import HomeError, HomePermissionDenied
from home.serializers import MemberHomeSerializer
from home.services import get_member_home


class MemberHomeView(ServiceAPIView):
    """Member-only; always the dashboard of request.user."""

    permission_classes = [IsVerifiedMember]
    service_error = HomeError
    error_status = {HomePermissionDenied: status.HTTP_403_FORBIDDEN}

    @extend_schema(responses={200: MemberHomeSerializer})
    def get(self, request):
        home = get_member_home(user=request.user)
        return success_response('Home.', data=MemberHomeSerializer(home).data)
