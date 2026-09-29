from django.urls import path

from paymethods.buy_views import (
    BuyMethodsView,
    DeactivateBuyMethodView,
    SetDefaultBuyMethodView,
)

app_name = 'buymethods'

# Mounted under api/buy-methods/: PERSONAL / BUY methods, separate from
# api/payout-methods/ (BUSINESS / SELL).
urlpatterns = [
    path('', BuyMethodsView.as_view(), name='buy-methods'),
    path('<int:method_id>/default/', SetDefaultBuyMethodView.as_view(), name='buy-method-default'),
    path('<int:method_id>/deactivate/', DeactivateBuyMethodView.as_view(), name='buy-method-deactivate'),
]
