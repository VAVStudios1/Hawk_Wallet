from django.urls import path

from orders.views import AdminCreateOfferView, AdminOrderDetailView, AdminOrdersView

app_name = 'admin-orders'

# Mounted under api/admin/; admin-only.
urlpatterns = [
    path('orders/', AdminOrdersView.as_view(), name='orders'),
    path('orders/<int:order_id>/', AdminOrderDetailView.as_view(), name='order-detail'),
    path('offers/', AdminCreateOfferView.as_view(), name='create-offer'),
]
