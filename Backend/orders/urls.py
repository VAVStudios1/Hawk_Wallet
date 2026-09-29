from django.urls import path

from orders.views import (
    ClaimableOffersView,
    ClaimOrderView,
    MemberOrderDetailView,
    MemberOrdersView,
)

app_name = 'orders'

urlpatterns = [
    path('', MemberOrdersView.as_view(), name='orders'),
    path('<int:order_id>/', MemberOrderDetailView.as_view(), name='order-detail'),
    path('offers/', ClaimableOffersView.as_view(), name='offers'),
    path('claim/', ClaimOrderView.as_view(), name='claim'),
]
