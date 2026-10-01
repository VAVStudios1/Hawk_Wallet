"""
Shared query helpers for the admin list endpoints: page-number pagination,
search and query-parameter parsing. Every helper raises the caller's own
`error` class (a domain service error with a stable `code`), so each app keeps
its usual error envelope. Pagination meta uses the same keys as the admin
support inbox: page, page_size, total, has_next.
"""
from datetime import date, datetime, time, timedelta
from zoneinfo import ZoneInfo

from django.conf import settings
from django.core.paginator import EmptyPage, PageNotAnInteger, Paginator
from django.db.models import Q
from django.utils import timezone

DEFAULT_PAGE_SIZE = 20
MAX_PAGE_SIZE = 100
MAX_SEARCH_LENGTH = 64


def paginate(queryset, page, page_size, *, error):
    """Return (items, meta) for one page of an already ordered `queryset`."""
    try:
        size = int(page_size) if page_size not in (None, '') else DEFAULT_PAGE_SIZE
    except (TypeError, ValueError):
        raise error(f'page_size must be a whole number from 1 to {MAX_PAGE_SIZE}.')
    if not 1 <= size <= MAX_PAGE_SIZE:
        raise error(f'page_size must be a whole number from 1 to {MAX_PAGE_SIZE}.')
    paginator = Paginator(queryset, size)
    try:
        current = paginator.page(page or 1)
    except (EmptyPage, PageNotAnInteger):
        raise error('page must be a whole number from 1 up to the last page.')
    return list(current.object_list), {
        'page': current.number,
        'page_size': size,
        'total': paginator.count,
        'has_next': current.has_next(),
    }


def choice(value, allowed, name, *, error):
    """None when absent; otherwise `value`, which must be one of `allowed` exactly."""
    if value in (None, ''):
        return None
    if value not in allowed:
        raise error(f'{name} must be one of: {", ".join(allowed)}.')
    return value


def boolean(value, name, *, error):
    """None when absent; True / False for 'true' / 'false' (any case)."""
    if value in (None, ''):
        return None
    lowered = value.lower()
    if lowered == 'true':
        return True
    if lowered == 'false':
        return False
    raise error(f'{name} must be true or false.')


def search_q(search, *, text_fields, digit_fields=(), error):
    """
    None for an empty search; otherwise a Q matching the term (case-insensitive
    substring) in any of `text_fields`, and - only when the term is all
    digits - in any of `digit_fields` (e.g. mobile number, UTR).
    """
    if search in (None, ''):
        return None
    term = search.strip()
    if not term:
        return None
    if len(term) > MAX_SEARCH_LENGTH:
        raise error(f'search must be at most {MAX_SEARCH_LENGTH} characters.')
    query = Q()
    for field in text_fields:
        query |= Q(**{f'{field}__icontains': term})
    if term.isdigit():
        for field in digit_fields:
            query |= Q(**{f'{field}__contains': term})
    return query


def date_value(value, name, *, error):
    """None when absent; otherwise a calendar date written YYYY-MM-DD."""
    if value in (None, ''):
        return None
    try:
        return date.fromisoformat(value)
    except (TypeError, ValueError):
        raise error(f'{name} must be a date written YYYY-MM-DD.')


def reporting_time_zone():
    """The business time zone admin calendar dates are interpreted in (settings.REPORTING_TIME_ZONE)."""
    return ZoneInfo(settings.REPORTING_TIME_ZONE)


def reporting_today():
    """Today's calendar date in the reporting time zone."""
    return timezone.localdate(timezone=reporting_time_zone())


def day_window(start, end):
    """
    The [start of `start`, start of the day after `end`) datetimes for two
    inclusive calendar dates, in the reporting time zone (Asia/Kolkata), so
    a "day" is an IST day whatever the server's TIME_ZONE.
    """
    tz = reporting_time_zone()
    return (
        datetime.combine(start, time.min, tzinfo=tz),
        datetime.combine(end + timedelta(days=1), time.min, tzinfo=tz),
    )
