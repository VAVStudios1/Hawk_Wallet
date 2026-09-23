from django.contrib.auth.hashers import check_password, make_password
from django.contrib.auth.models import AbstractBaseUser, BaseUserManager, PermissionsMixin
from django.core.validators import RegexValidator
from django.db import models
from django.utils import timezone

mobile_number_validator = RegexValidator(
    regex=r'^[6-9]\d{9}$',
    message='Enter a valid 10-digit mobile number.',
)


class UserManager(BaseUserManager):
    use_in_migrations = True

    def _create_user(self, mobile_number, name, password, **extra_fields):
        if not mobile_number:
            raise ValueError('Users must have a mobile number.')
        if not name:
            raise ValueError('Users must have a name.')
        user = self.model(mobile_number=mobile_number, name=name, **extra_fields)
        user.set_password(password)
        user.save(using=self._db)
        return user

    def create_user(self, mobile_number, name, password=None, **extra_fields):
        # setdefault (not forced) so a trusted internal caller (e.g. an
        # admin creating another admin) can still pass role explicitly.
        # Public registration (RegisterSerializer) never passes role, so it
        # always lands on MEMBER.
        extra_fields.setdefault('is_staff', False)
        extra_fields.setdefault('is_superuser', False)
        extra_fields.setdefault('role', self.model.Role.MEMBER)
        return self._create_user(mobile_number, name, password, **extra_fields)

    def create_superuser(self, mobile_number, name, password=None, **extra_fields):
        extra_fields.setdefault('is_staff', True)
        extra_fields.setdefault('is_superuser', True)
        extra_fields.setdefault('is_active', True)
        extra_fields.setdefault('is_verified', True)
        # Forced, not setdefault: a superuser must never end up as MEMBER.
        extra_fields['role'] = self.model.Role.SUPER_ADMIN

        if extra_fields.get('is_staff') is not True:
            raise ValueError('Superuser must have is_staff=True.')
        if extra_fields.get('is_superuser') is not True:
            raise ValueError('Superuser must have is_superuser=True.')
        if extra_fields.get('role') != self.model.Role.SUPER_ADMIN:
            raise ValueError('Superuser must have role=SUPER_ADMIN.')

        return self._create_user(mobile_number, name, password, **extra_fields)


class User(AbstractBaseUser, PermissionsMixin):
    class Role(models.TextChoices):
        MEMBER = 'MEMBER', 'Member'
        ADMIN = 'ADMIN', 'Admin'
        SUPER_ADMIN = 'SUPER_ADMIN', 'Super Admin'

    name = models.CharField(max_length=150)
    mobile_number = models.CharField(
        max_length=10,
        unique=True,
        validators=[mobile_number_validator],
    )
    role = models.CharField(
        max_length=20,
        choices=Role.choices,
        default=Role.MEMBER,
    )

    is_active = models.BooleanField(default=True)
    is_staff = models.BooleanField(default=False)
    is_verified = models.BooleanField(
        default=False,
        help_text='Set once the user has completed OTP verification.',
    )

    date_joined = models.DateTimeField(default=timezone.now)

    objects = UserManager()

    USERNAME_FIELD = 'mobile_number'
    REQUIRED_FIELDS = ['name']

    def __str__(self):
        return self.mobile_number


class OTP(models.Model):
    class Purpose(models.TextChoices):
        REGISTRATION = 'registration', 'Registration'
        LOGIN = 'login', 'Login'

    user = models.ForeignKey(User, on_delete=models.CASCADE, related_name='otps')
    purpose = models.CharField(max_length=20, choices=Purpose.choices)
    code_hash = models.CharField(max_length=128)

    attempts = models.PositiveSmallIntegerField(default=0)
    is_used = models.BooleanField(default=False)

    created_at = models.DateTimeField(auto_now_add=True)
    expires_at = models.DateTimeField()

    class Meta:
        indexes = [
            models.Index(fields=['user', 'purpose', 'is_used']),
        ]

    def set_code(self, raw_code):
        self.code_hash = make_password(raw_code)

    def check_code(self, raw_code):
        return check_password(raw_code, self.code_hash)

    def is_expired(self):
        return timezone.now() >= self.expires_at

    def __str__(self):
        return f'{self.user.mobile_number} - {self.purpose}'
