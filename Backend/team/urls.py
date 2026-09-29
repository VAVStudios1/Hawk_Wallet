from django.urls import path

from team.views import CommissionHistoryView, ReferralInfoView, TeamMembersView, TeamSummaryView

app_name = 'team'

urlpatterns = [
    path('referral/', ReferralInfoView.as_view(), name='referral'),
    path('summary/', TeamSummaryView.as_view(), name='summary'),
    path('members/', TeamMembersView.as_view(), name='members'),
    path('commissions/', CommissionHistoryView.as_view(), name='commissions'),
]
