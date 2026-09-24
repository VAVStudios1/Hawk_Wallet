from pathlib import Path
from uuid import uuid4

from django.core.exceptions import ValidationError
from django.core.validators import FileExtensionValidator, RegexValidator
from PIL import Image, UnidentifiedImageError

upi_id_validator = RegexValidator(
    regex=r'^[A-Za-z0-9._-]{2,200}@[A-Za-z][A-Za-z0-9]{1,63}$',
    message='Enter a valid UPI ID (e.g. name@bank).',
)

provider_code_validator = RegexValidator(
    regex=r'^[A-Z][A-Z0-9_]{1,31}$',
    message='Use UPPER_SNAKE_CASE, e.g. PHONEPE.',
)

# UTR / UPI reference: exactly 12 digits, stored as a string so leading zeros
# survive. The same pattern backs the PayIn/Payout DB check constraints.
UTR_PATTERN = r'^[0-9]{12}$'
UTR_LENGTH = 12

utr_validator = RegexValidator(
    regex=UTR_PATTERN,
    message='UTR must be exactly 12 digits.',
)

merchant_code_validator = RegexValidator(
    regex=r'^\d{4}$',
    message='Merchant category code must be 4 digits.',
)

QR_ALLOWED_EXTENSIONS = ('png', 'jpg', 'jpeg', 'webp')
QR_ALLOWED_FORMATS = ('PNG', 'JPEG', 'WEBP')
QR_MAX_BYTES = 5 * 1024 * 1024
QR_MAX_DIMENSION = 4096

qr_extension_validator = FileExtensionValidator(allowed_extensions=QR_ALLOWED_EXTENSIONS)


def validate_qr_image(file):
    """Content check with Pillow; the extension alone proves nothing."""
    if file.size > QR_MAX_BYTES:
        raise ValidationError('QR image must be 5 MB or smaller.')
    try:
        file.seek(0)
        with Image.open(file) as image:
            image_format = image.format
            width, height = image.size
            image.verify()
    except (UnidentifiedImageError, OSError, SyntaxError, Image.DecompressionBombError):
        raise ValidationError('Upload a valid PNG, JPEG or WEBP image.')
    finally:
        file.seek(0)

    if image_format not in QR_ALLOWED_FORMATS:
        raise ValidationError('Upload a valid PNG, JPEG or WEBP image.')
    if width > QR_MAX_DIMENSION or height > QR_MAX_DIMENSION:
        raise ValidationError(f'QR image must be at most {QR_MAX_DIMENSION}px on each side.')


def _randomized_name(prefix, filename):
    # Never keep the client-supplied filename; keep only a whitelisted extension.
    extension = Path(filename).suffix.lower()
    if extension.lstrip('.') not in QR_ALLOWED_EXTENSIONS:
        extension = ''
    return f'{prefix}/{uuid4().hex}{extension}'


def payout_qr_upload_to(instance, filename):
    return _randomized_name('payout_qr', filename)


def platform_qr_upload_to(instance, filename):
    return _randomized_name('platform_qr', filename)
