from django.urls import path

from home.views import MemberHomeView

app_name = 'home'

urlpatterns = [
    path('', MemberHomeView.as_view(), name='home'),
]
