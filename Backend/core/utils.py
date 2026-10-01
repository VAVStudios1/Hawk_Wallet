from rest_framework.response import Response


def success_response(message, data=None, status_code=200):
    return Response(
        {'success': True, 'message': message, 'data': data or {}},
        status=status_code,
    )


def error_response(message, errors=None, status_code=400):
    return Response(
        {'success': False, 'message': message, 'errors': errors or {}},
        status=status_code,
    )


def mask_mobile(mobile_number):
    # Admin and team views never reveal a member's full mobile number: ******3210.
    return '*' * (len(mobile_number) - 4) + mobile_number[-4:] if mobile_number else ''
