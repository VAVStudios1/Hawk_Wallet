"""
UPI payment QR decoding for admin-uploaded Pay-In QRs (e.g. a BharatPe
merchant QR).

decode_upi_qr reads the image and returns the QR's raw text; parse_upi_payload
validates that text as a upi://pay payload and returns its parameters in
their original order. The raw text is what gets stored: the member's payment
URI is rebuilt from it at pay-in time with the server's order amount, so the
merchant's own parameters (pn, mc, mode, orgid, sign, ...) reach the UPI app
unchanged.
"""
import re
from urllib.parse import unquote

import zxingcpp
from django.core.exceptions import ValidationError
from PIL import Image, ImageOps

from paymethods.validators import (
    merchant_code_validator,
    qr_extension_validator,
    upi_id_validator,
    validate_qr_image,
)

UPI_PAY_PREFIX = 'upi://pay?'
MAX_PAYLOAD_LENGTH = 1000
SUPPORTED_CURRENCY = 'INR'
# UPI parameter names are short lowercase words (pa, pn, mc, orgid, ...).
PARAM_NAME = re.compile(r'[a-z]{1,20}')
# Control characters (incl. newlines) never belong in a UPI payload.
CONTROL_CHARS = re.compile(r'[\x00-\x1f\x7f]')

NOT_A_QR_MESSAGE = 'No QR code was found in this image. Please upload a clear photo of the QR.'
MULTIPLE_QR_MESSAGE = 'This image contains more than one QR code. Please upload a single QR.'
NOT_UPI_MESSAGE = 'This QR is not a UPI payment QR.'


class UpiQrError(ValueError):
    """The upload or its content is not a usable UPI payment QR."""


def decode_upi_qr(upload):
    """
    Return the text of the single QR code in the uploaded image.

    The image passes the same checks as payout-method QR uploads (type,
    size, dimensions) before any pixels are decoded.
    """
    try:
        qr_extension_validator(upload)
        validate_qr_image(upload)
    except ValidationError as exc:
        raise UpiQrError(exc.messages[0])

    upload.seek(0)
    try:
        with Image.open(upload) as original:
            original.load()
            image = ImageOps.exif_transpose(original).convert('RGB')
    except (OSError, SyntaxError, ValueError, Image.DecompressionBombError):
        raise UpiQrError('This image is incomplete or damaged. Please upload the QR again.')
    finally:
        upload.seek(0)

    results = [r for r in zxingcpp.read_barcodes(image, formats=zxingcpp.BarcodeFormat.QRCode) if r.valid]
    if not results:
        raise UpiQrError(NOT_A_QR_MESSAGE)
    if len({r.text for r in results}) > 1:
        raise UpiQrError(MULTIPLE_QR_MESSAGE)
    return results[0].text


def parse_upi_payload(payload):
    """
    Validate `payload` as a upi://pay payload and return its parameters as a
    list of (name, value) pairs in their original order, values
    percent-decoded ('+' is kept literally, never read as a space).

    Requires a valid pa; cu, when present, must be INR; mc, when present,
    must be 4 digits. Names are lowercased; a repeated name is refused.
    """
    if (
        not isinstance(payload, str)
        or len(payload) > MAX_PAYLOAD_LENGTH
        or CONTROL_CHARS.search(payload)
        or payload[:len(UPI_PAY_PREFIX)].lower() != UPI_PAY_PREFIX
    ):
        raise UpiQrError(NOT_UPI_MESSAGE)

    params = []
    seen = set()
    for part in payload[len(UPI_PAY_PREFIX):].split('&'):
        if not part:
            continue
        name, sep, raw_value = part.partition('=')
        name = name.lower()
        if not sep or PARAM_NAME.fullmatch(name) is None or name in seen:
            raise UpiQrError(NOT_UPI_MESSAGE)
        seen.add(name)
        params.append((name, unquote(raw_value)))

    values = dict(params)
    try:
        upi_id_validator(values.get('pa', ''))
        if 'mc' in values:
            merchant_code_validator(values['mc'])
    except ValidationError:
        raise UpiQrError(NOT_UPI_MESSAGE)
    if values.get('cu', SUPPORTED_CURRENCY) != SUPPORTED_CURRENCY:
        raise UpiQrError('This QR is not for Indian Rupee (INR) payments.')
    return params
