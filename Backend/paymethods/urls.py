from django.urls import path

from paymethods.views import (
    DeactivatePayoutMethodView,
    AddPayoutMethodView,
    SetDefaultPayoutMethodView,
)

app_name = 'paymethods'

urlpatterns = [
    path('', AddPayoutMethodView.as_view(), name='payout-methods'),
    path('<int:method_id>/default/', SetDefaultPayoutMethodView.as_view(), name='payout-method-default'),
    path('<int:method_id>/deactivate/', DeactivatePayoutMethodView.as_view(), name='payout-method-deactivate'),
]
