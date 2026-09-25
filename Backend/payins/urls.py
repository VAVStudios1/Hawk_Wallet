from django.urls import path

from payins.views import PayInIntentView, SubmitPayInView

app_name = 'payins'

# Mounted under api/orders/: pay-ins are always for one of the member's orders.
urlpatterns = [
    path('<int:order_id>/pay-in-intent/', PayInIntentView.as_view(), name='pay-in-intent'),
    path('<int:order_id>/pay-in/', SubmitPayInView.as_view(), name='pay-in'),
]
