import hashlib
from io import BytesIO
from typing import NamedTuple

from django.core.files.base import ContentFile
from PIL import Image, ImageOps

from paymethods.validators import qr_extension_validator, validate_qr_image


class SanitizedQR(NamedTuple):
    content: ContentFile
    sha256: str


def sanitize_qr_image(upload):
    """
    Validate an uploaded QR image, then re-encode it as PNG from raw pixels.

    Rebuilding from pixel bytes drops every metadata block (EXIF incl. GPS,
    XMP, ICC profiles, PNG text chunks). EXIF orientation is applied first so
    the stored image still reads the right way up. The SHA-256 is taken over
    the exact bytes that will be stored.

    Raises django.core.exceptions.ValidationError for an unacceptable file.
    """
    qr_extension_validator(upload)
    validate_qr_image(upload)

    upload.seek(0)
    with Image.open(upload) as original:
        original.load()
        oriented = ImageOps.exif_transpose(original)
        has_alpha = oriented.mode in ('RGBA', 'LA', 'PA') or 'transparency' in oriented.info
        pixels = oriented.convert('RGBA' if has_alpha else 'RGB')
    upload.seek(0)

    clean = Image.frombytes(pixels.mode, pixels.size, pixels.tobytes())
    buffer = BytesIO()
    clean.save(buffer, format='PNG')
    data = buffer.getvalue()
    return SanitizedQR(ContentFile(data, name='qr.png'), hashlib.sha256(data).hexdigest())
