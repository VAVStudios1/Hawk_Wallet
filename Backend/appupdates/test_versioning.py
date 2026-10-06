import pytest

from appupdates.versioning import AppVersion, InvalidVersion


def v(value):
    return AppVersion.parse(value)


def test_versions_compare_numerically_not_as_strings():
    assert v('1.0.9') < v('1.0.10')
    assert v('1.0.10') > v('1.0.9')
    assert v('1.10.0') > v('1.9.99')
    assert v('2.0.0') > v('1.99.99')


def test_equal_versions():
    assert v('1.0.7') == v('1.0.7')
    assert not v('1.0.7') < v('1.0.7')
    assert str(v('1.0.10')) == '1.0.10'


@pytest.mark.parametrize('value', [
    '', '1', '1.0', '1.0.0.0', 'v1.0.0', '1.0.a', '1..0', '01.0.0', '1.00.0',
    '1.0.0-beta', ' 1.0.0', '1.0.0 ', '1.0.0\n', '-1.0.0', '1234567.0.0', '1.0.0+5', None, 100,
])
def test_malformed_versions_are_rejected(value):
    with pytest.raises(InvalidVersion):
        AppVersion.parse(value)
