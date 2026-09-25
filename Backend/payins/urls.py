from django.urls import path

from payins.views import ConfirmPayInView, PayInIntentView, RejectPayInView, SubmitPayInView

app_name = 'payins'

# Mounted under api/. Member pay-ins are always for one of the member's own
# orders; admin review lives under admin/.
urlpatterns = [
    path('orders/<int:order_id>/pay-in-intent/', PayInIntentView.as_view(), name='pay-in-intent'),
    path('orders/<int:order_id>/pay-in/', SubmitPayInView.as_view(), name='pay-in'),
    path('admin/pay-ins/<int:payin_id>/confirm/', ConfirmPayInView.as_view(), name='admin-pay-in-confirm'),
    path('admin/pay-ins/<int:payin_id>/reject/', RejectPayInView.as_view(), name='admin-pay-in-reject'),
]
