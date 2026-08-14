import re


_PHONE_SEPARATORS = re.compile(r"[\s-]+")
_MAINLAND_MOBILE = re.compile(r"\d{11}")


def normalize_phone(value: object) -> str:
    return _PHONE_SEPARATORS.sub("", str(value).strip())


def mask_phone(value: object) -> str:
    phone = normalize_phone(value)
    if not _MAINLAND_MOBILE.fullmatch(phone):
        return "***"
    return f"{phone[:3]}****{phone[-4:]}"
