from rest_framework import serializers


class UpdateCheckSerializer(serializers.Serializer):
    """
    Update-check result. When no usable release is published, the release
    fields are null, release_notes is empty and nothing is forced.
    """

    current_version = serializers.CharField()
    latest_version = serializers.CharField(allow_null=True)
    minimum_supported_version = serializers.CharField(allow_null=True)
    supported = serializers.BooleanField()
    update_available = serializers.BooleanField()
    force_update = serializers.BooleanField()
    apk_url = serializers.URLField(allow_null=True)
    apk_sha256 = serializers.CharField(allow_null=True)
    apk_size = serializers.IntegerField(allow_null=True)
    release_notes = serializers.ListField(child=serializers.CharField())
