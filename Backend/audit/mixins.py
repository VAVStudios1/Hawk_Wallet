"""
Model-level guards that keep financial history from being silently rewritten.

These guards cover the normal ORM path (instance.save() / instance.delete()).
QuerySet.update() bypasses save(), so services must never use it on
immutable fields; append-only models block it outright via
AppendOnlyQuerySet.
"""
from django.db import models
from django.db.models import F
from django.db.models.functions import Round
from django.db.models.lookups import Exact


def whole_rupees(field_name, name):
    """
    CheckConstraint: the money field holds a whole-rupee value. Columns keep
    2 decimal places so a fractional value is rejected, never silently
    rounded by the database.
    """
    return models.CheckConstraint(
        condition=Exact(F(field_name), Round(F(field_name))),
        name=name,
    )


class ImmutableFieldError(Exception):
    """Raised when a save would change a field that must never change."""


class AppendOnlyError(Exception):
    """Raised when an append-only record would be edited or deleted."""


def _comparable(field, value):
    # FileFields come back from .values() as a name string but live on the
    # instance as a FieldFile; compare both as the stored name.
    if isinstance(field, models.FileField):
        return getattr(value, 'name', value) or ''
    return value


class ImmutableFieldsMixin:
    """
    Rejects saves that change any field listed in `immutable_fields` on an
    existing row. Subclasses can override `immutability_applies()` to make
    the rule conditional (e.g. only once a record is used by financial
    history).

    Once the stored `status` is in `final_statuses`, the whole record is
    frozen: no field (status included) may change.
    """

    immutable_fields = ()
    final_statuses = ()

    def immutability_applies(self):
        return True

    def save(self, *args, **kwargs):
        if (
            (self.immutable_fields or self.final_statuses)
            and not self._state.adding
            and self.pk is not None
        ):
            self._check_immutable_fields()
        super().save(*args, **kwargs)

    def _check_immutable_fields(self):
        concrete = [f for f in self._meta.concrete_fields if not f.primary_key]
        stored = (
            type(self)._base_manager
            .filter(pk=self.pk)
            .values(*(field.attname for field in concrete))
            .first()
        )
        if stored is None:
            return
        changed = {
            field.name
            for field in concrete
            if _comparable(field, stored[field.attname])
            != _comparable(field, getattr(self, field.attname))
        }
        if self.final_statuses and stored.get('status') in self.final_statuses and changed:
            raise ImmutableFieldError(
                f'{type(self).__name__} is {stored["status"]} and can no longer change.'
            )
        locked = sorted(changed.intersection(self.immutable_fields))
        if locked and self.immutability_applies():
            raise ImmutableFieldError(
                f'{type(self).__name__} fields cannot be changed: {", ".join(locked)}'
            )


class NoDeleteQuerySet(models.QuerySet):
    def delete(self):
        raise AppendOnlyError(f'{self.model.__name__} records cannot be deleted.')


class UndeletableModel(models.Model):
    """Financial records are never hard-deleted; corrections are new records."""

    objects = NoDeleteQuerySet.as_manager()

    class Meta:
        abstract = True

    def delete(self, *args, **kwargs):
        raise AppendOnlyError(f'{type(self).__name__} records cannot be deleted.')


class AppendOnlyQuerySet(models.QuerySet):
    def update(self, **kwargs):
        raise AppendOnlyError(f'{self.model.__name__} rows are append-only.')

    def delete(self):
        raise AppendOnlyError(f'{self.model.__name__} rows are append-only.')


class AppendOnlyModel(models.Model):
    """Rows can be inserted, never edited or deleted through the ORM."""

    objects = AppendOnlyQuerySet.as_manager()

    class Meta:
        abstract = True

    def save(self, *args, **kwargs):
        if not self._state.adding:
            raise AppendOnlyError(f'{type(self).__name__} rows are append-only.')
        super().save(*args, **kwargs)

    def delete(self, *args, **kwargs):
        raise AppendOnlyError(f'{type(self).__name__} rows are append-only.')
