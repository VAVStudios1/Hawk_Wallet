from django.urls import path

from payouts.views import PayoutMethodForAdminView, CreatePayoutView, MarkPayoutPaidView

app_name = 'payouts'

# Mounted under api/; every payout endpoint is admin-only.
urlpatterns = [
    path('admin/orders/<int:order_id>/payouts/', CreatePayoutView.as_view(), name='admin-create-payout'),
    path('admin/payouts/<int:payout_id>/paid/', MarkPayoutPaidView.as_view(), name='admin-payout-paid'),
    path(
        'admin/payouts/<int:payout_id>/payout-method/',
        PayoutMethodForAdminView.as_view(),
        name='admin-payout-method',
    ),
]
