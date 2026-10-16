from datetime import timedelta

import pytest
from django.utils import timezone


@pytest.fixture
def clock(monkeypatch):
    """Freezes django.utils.timezone.now(); advance() moves it forward."""

    class Clock:
        def __init__(self):
            self.now = timezone.now()

        def __call__(self):
            return self.now

        def advance(self, **delta):
            self.now += timedelta(**delta)

    frozen = Clock()
    monkeypatch.setattr('django.utils.timezone.now', frozen)
    return frozen
