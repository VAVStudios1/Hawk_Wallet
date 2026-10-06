from django.urls import path

from appupdates.views import ApkDownloadView, UpdateCheckView

app_name = 'appupdates'

# Mounted under api/app/, which app-version enforcement always exempts.
urlpatterns = [
    path('update-check/', UpdateCheckView.as_view(), name='update-check'),
    path('releases/<str:version>/apk/', ApkDownloadView.as_view(), name='apk-download'),
]
