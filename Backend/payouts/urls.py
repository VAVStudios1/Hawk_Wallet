from django.urls import path

from payouts.views import (
    AdminPayoutDetailView,
    AdminPayoutsView,
    CancelPayoutView,
    CreatePayoutView,
    MarkPayoutPaidView,
    PayoutMethodForAdminView,
    PayoutQrForAdminView,
)

app_name = 'payouts'

# Mounted under api/; every payout endpoint is admin-only.
urlpatterns = [
    path('admin/payouts/', AdminPayoutsView.as_view(), name='admin-payouts'),
    path('admin/payouts/<int:payout_id>/', AdminPayoutDetailView.as_view(), name='admin-payout-detail'),
    path('admin/orders/<int:order_id>/payouts/', CreatePayoutView.as_view(), name='admin-create-payout'),
    path('admin/payouts/<int:payout_id>/paid/', MarkPayoutPaidView.as_view(), name='admin-payout-paid'),
    path('admin/payouts/<int:payout_id>/cancel/', CancelPayoutView.as_view(), name='admin-payout-cancel'),
    path(
        'admin/payouts/<int:payout_id>/payout-method/',
        PayoutMethodForAdminView.as_view(),
        name='admin-payout-method',
    ),
    path(
        'admin/payouts/<int:payout_id>/payout-method/qr/',
        PayoutQrForAdminView.as_view(),
        name='admin-payout-method-qr',
    ),
]
