from django.urls import path

from orders.views import ClaimOrderView

app_name = 'orders'

urlpatterns = [
    path('claim/', ClaimOrderView.as_view(), name='claim'),
]
