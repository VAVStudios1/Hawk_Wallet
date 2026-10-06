from django.urls import path

from home.views import MemberHomeView, MemberTransactionsView

app_name = 'home'

urlpatterns = [
    path('', MemberHomeView.as_view(), name='home'),
    path('transactions/', MemberTransactionsView.as_view(), name='transactions'),
]
