"""
Numeric MAJOR.MINOR.PATCH app versions (e.g. 1.0.7), compared as numbers -
never as strings - so 1.0.10 > 1.0.9.
"""
import re
from dataclasses import dataclass

from django.core.validators import RegexValidator

# No leading zeros, at most 6 digits per part. The same pattern backs the
# AppRelease DB check constraints, so it sticks to [0-9] (portable regex).
VERSION_PATTERN = r'^(0|[1-9][0-9]{0,5})\.(0|[1-9][0-9]{0,5})\.(0|[1-9][0-9]{0,5})$'
VERSION_MAX_LENGTH = 20

_VERSION_RE = re.compile(VERSION_PATTERN)

version_validator = RegexValidator(
    regex=VERSION_PATTERN,
    message='Use a MAJOR.MINOR.PATCH version such as 1.0.7.',
)


class InvalidVersion(ValueError):
    """The value is not a MAJOR.MINOR.PATCH version."""


@dataclass(frozen=True, order=True)
class AppVersion:
    major: int
    minor: int
    patch: int

    @classmethod
    def parse(cls, value):
        if not isinstance(value, str):
            raise InvalidVersion('Version must be a string.')
        match = _VERSION_RE.fullmatch(value)
        if match is None:
            raise InvalidVersion(f'Not a MAJOR.MINOR.PATCH version: {value[:VERSION_MAX_LENGTH]!r}')
        return cls(*(int(part) for part in match.groups()))

    def __str__(self):
        return f'{self.major}.{self.minor}.{self.patch}'
