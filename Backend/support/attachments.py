"""
Validation and sanitizing of support chat attachments.

Follows the payout-QR pattern (paymethods.validators / paymethods.qr): the
client's filename and MIME type are never trusted, images are proven with
Pillow and re-encoded from raw pixels (dropping EXIF incl. GPS, XMP, ICC and
text chunks), and files land in private storage under a random name.

Only two kinds are accepted:
  IMAGE - PNG, JPEG or WEBP, at most 10 MB and 8192 px per side.
  FILE  - PDF only, at most 10 MB, proven by its header and trailer.
"""
import hashlib
import logging
import re
import unicodedata
from io import BytesIO
from pathlib import PurePosixPath
from typing import NamedTuple
from uuid import uuid4

from django.core.exceptions import ValidationError
from django.core.files.base import ContentFile
from PIL import Image, ImageOps, UnidentifiedImageError

logger = logging.getLogger(__name__)

MAX_ATTACHMENT_BYTES = 10 * 1024 * 1024

IMAGE_ALLOWED_EXTENSIONS = ('png', 'jpg', 'jpeg', 'webp')
IMAGE_ALLOWED_FORMATS = ('PNG', 'JPEG', 'WEBP')
IMAGE_MAX_DIMENSION = 8192
JPEG_QUALITY = 90

FILE_ALLOWED_EXTENSIONS = ('pdf',)
PDF_HEADER = b'%PDF-'
PDF_TRAILER = b'%%EOF'
# The header may follow a little leading junk and the trailer may be
# followed by whitespace; both are only searched near the file's ends.
PDF_HEADER_WINDOW = 1024
PDF_TRAILER_WINDOW = 2048
# PDF features that run code or carry other files. A cheap raw-byte scan:
# defence in depth only, since compressed object streams can hide names.
PDF_FORBIDDEN_TOKENS = (b'/JavaScript', b'/JS', b'/Launch', b'/EmbeddedFile', b'/RichMedia')

DISPLAY_NAME_MAX_LENGTH = 100

SIZE_MESSAGE = 'Attachments must be 10 MB or smaller.'
IMAGE_MESSAGE = 'Upload a valid PNG, JPEG or WEBP image.'
DAMAGED_IMAGE_MESSAGE = 'This image is incomplete or damaged. Please upload it again.'
PDF_MESSAGE = 'Only PDF files can be attached.'
PDF_ACTIVE_CONTENT_MESSAGE = 'PDF files with scripts, actions or embedded files are not allowed.'


class SanitizedAttachment(NamedTuple):
    content: ContentFile
    display_name: str
    content_type: str
    size: int
    sha256: str


def _extension(filename):
    return PurePosixPath(str(filename).replace('\\', '/')).suffix.lower().lstrip('.')


def display_name(filename, extension, fallback_stem):
    """
    A safe name to show in the chat: the client's base name without any
    path, control or separator characters, cut to 100 characters, always
    ending in `extension` (the stored type, not the client's claim). Only
    shown to participants; never used as a storage path.
    """
    base = str(filename or '').replace('\\', '/').rsplit('/', 1)[-1]
    base = unicodedata.normalize('NFKC', base)
    stem = base.rsplit('.', 1)[0] if '.' in base else base
    stem = ''.join(ch for ch in stem if unicodedata.category(ch)[0] != 'C')
    stem = re.sub(r'[<>:"/\\|?*]', '', stem)
    stem = re.sub(r'\s+', ' ', stem).strip(' .')
    stem = stem[: DISPLAY_NAME_MAX_LENGTH - len(extension) - 1] or fallback_stem
    return f'{stem}.{extension}'


def _check_size(upload):
    if upload.size is None or upload.size <= 0:
        raise ValidationError('The file is empty.')
    if upload.size > MAX_ATTACHMENT_BYTES:
        raise ValidationError(SIZE_MESSAGE)


def sanitize_image(upload):
    """
    Validate an uploaded image and re-encode it from raw pixels: JPEG when
    it has no transparency, PNG otherwise. EXIF orientation is applied first.
    Raises ValidationError for an unacceptable file.
    """
    _check_size(upload)
    if _extension(upload.name) not in IMAGE_ALLOWED_EXTENSIONS:
        raise ValidationError(IMAGE_MESSAGE)
    try:
        upload.seek(0)
        with Image.open(upload) as image:
            image_format = image.format
            width, height = image.size
            image.verify()
    except (UnidentifiedImageError, OSError, SyntaxError, ValueError, Image.DecompressionBombError):
        raise ValidationError(IMAGE_MESSAGE)
    finally:
        upload.seek(0)
    if image_format not in IMAGE_ALLOWED_FORMATS:
        raise ValidationError(IMAGE_MESSAGE)
    if width > IMAGE_MAX_DIMENSION or height > IMAGE_MAX_DIMENSION:
        raise ValidationError(f'Images must be at most {IMAGE_MAX_DIMENSION}px on each side.')

    # verify() does not decode pixels, so a truncated file first fails here.
    try:
        with Image.open(upload) as original:
            original.load()
            oriented = ImageOps.exif_transpose(original)
            has_alpha = oriented.mode in ('RGBA', 'LA', 'PA') or 'transparency' in oriented.info
            pixels = oriented.convert('RGBA' if has_alpha else 'RGB')
    except (OSError, SyntaxError, ValueError, Image.DecompressionBombError) as exc:
        # The decoder's own reason (never image content) for diagnosing rejects.
        logger.warning(
            'Support image rejected as damaged: %s: %s (format=%s, size=%sx%s, bytes=%s)',
            type(exc).__name__, exc, image_format, width, height, upload.size,
        )
        raise ValidationError(DAMAGED_IMAGE_MESSAGE)
    finally:
        upload.seek(0)

    clean = Image.frombytes(pixels.mode, pixels.size, pixels.tobytes())
    buffer = BytesIO()
    if has_alpha:
        clean.save(buffer, format='PNG')
        extension, content_type = 'png', 'image/png'
    else:
        clean.save(buffer, format='JPEG', quality=JPEG_QUALITY)
        extension, content_type = 'jpg', 'image/jpeg'
    data = buffer.getvalue()
    if len(data) > MAX_ATTACHMENT_BYTES:
        raise ValidationError(SIZE_MESSAGE)
    return SanitizedAttachment(
        content=ContentFile(data, name=f'image.{extension}'),
        display_name=display_name(upload.name, extension, 'image'),
        content_type=content_type,
        size=len(data),
        sha256=hashlib.sha256(data).hexdigest(),
    )


def sanitize_pdf(upload):
    """
    Validate an uploaded PDF by content (header, trailer, no active-content
    markers) and return it byte-for-byte. Raises ValidationError otherwise.
    """
    _check_size(upload)
    if _extension(upload.name) not in FILE_ALLOWED_EXTENSIONS:
        raise ValidationError(PDF_MESSAGE)
    upload.seek(0)
    data = upload.read(MAX_ATTACHMENT_BYTES + 1)
    upload.seek(0)
    if len(data) > MAX_ATTACHMENT_BYTES:
        raise ValidationError(SIZE_MESSAGE)
    if PDF_HEADER not in data[:PDF_HEADER_WINDOW] or PDF_TRAILER not in data[-PDF_TRAILER_WINDOW:]:
        raise ValidationError(PDF_MESSAGE)
    if any(token in data for token in PDF_FORBIDDEN_TOKENS):
        raise ValidationError(PDF_ACTIVE_CONTENT_MESSAGE)
    return SanitizedAttachment(
        content=ContentFile(data, name='file.pdf'),
        display_name=display_name(upload.name, 'pdf', 'document'),
        content_type='application/pdf',
        size=len(data),
        sha256=hashlib.sha256(data).hexdigest(),
    )


def support_attachment_upload_to(instance, filename):
    # Never the client's name: a random name with the sanitized extension.
    extension = _extension(filename)
    if extension not in ('png', 'jpg', 'pdf'):
        extension = 'bin'
    return f'support/{uuid4().hex}.{extension}'
