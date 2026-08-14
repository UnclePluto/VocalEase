import re


_STORED_PHONE_SEPARATORS = re.compile(r"[\s-]+")
_STORED_MAINLAND_MOBILE = re.compile(r"\d{11}")


def normalize_phone(value: object) -> str:
    return _STORED_PHONE_SEPARATORS.sub("", str(value).strip())


def mask_phone(value: object) -> str:
    phone = normalize_phone(value)
    if not _STORED_MAINLAND_MOBILE.fullmatch(phone):
        return "***"
    return f"{phone[:3]}****{phone[-4:]}"


INVALID_REQUEST_ID = "invalid-request-id"
REQUEST_ID_PATTERN = re.compile(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,63}\Z")

_DIGIT = r"[0-9０-９]"
_SEPARATOR = r"[\s.．。/／\\·・\-‐‑‒–—―−﹣－\u200b\u200c\u200d\u2060\ufeff]"
_PHONE_CANDIDATE = re.compile(
    rf"(?<![A-Za-z0-9０-９])"
    rf"(?P<candidate>(?:(?:[+＋]{_SEPARATOR}*)?[8８]{_SEPARATOR}*[6６]{_SEPARATOR}*)?"
    rf"{_DIGIT}(?:{_SEPARATOR}*{_DIGIT}){{10}})"
    rf"(?![A-Za-z0-9０-９])"
)


def _ascii_digits(value: str) -> str:
    digits = []
    for character in value:
        if "0" <= character <= "9":
            digits.append(character)
        elif "０" <= character <= "９":
            digits.append(chr(ord("0") + ord(character) - ord("０")))
    return "".join(digits)


def _is_chinese_mobile_candidate(candidate: str) -> bool:
    digits = _ascii_digits(candidate)
    if len(digits) == 13 and digits.startswith("86"):
        digits = digits[2:]
    return bool(re.fullmatch(r"1[3-9][0-9]{9}", digits))


def contains_chinese_mobile(value: str) -> bool:
    return any(
        _is_chinese_mobile_candidate(match.group("candidate"))
        for match in _PHONE_CANDIDATE.finditer(value)
    )


def redact_phone_numbers(value: str, replacement: str = "[PHONE_REDACTED]") -> str:
    def replace(match: re.Match) -> str:
        candidate = match.group("candidate")
        return replacement if _is_chinese_mobile_candidate(candidate) else candidate

    return _PHONE_CANDIDATE.sub(replace, value)


def is_safe_request_id(value: object) -> bool:
    return (
        isinstance(value, str)
        and bool(REQUEST_ID_PATTERN.fullmatch(value))
        and not contains_chinese_mobile(value)
    )


def sanitize_request_id(value: object, *, invalid_value: str = INVALID_REQUEST_ID) -> str:
    if value == "":
        return ""
    return value if is_safe_request_id(value) else invalid_value
