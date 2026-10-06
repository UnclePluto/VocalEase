"""版本化参考音高的边界合同；不接受非有限数或混音推测。"""
import json
import math
from rest_framework.exceptions import ValidationError

MAX_BYTES = 10 * 1024 * 1024


def validate_pitch_document(value: object, *, duration_ms: int) -> dict:
    def invalid():
        raise ValidationError({'document': '参考音高区段、来源或大小无效'})
    try:
        if len(json.dumps(value, allow_nan=False, ensure_ascii=False).encode()) > MAX_BYTES:
            invalid()
    except (ValueError, TypeError, OverflowError):
        invalid()
    if not isinstance(value, dict) or type(value.get('schema_version')) is not int or value['schema_version'] != 1:
        invalid()
    origin = value.get('origin')
    if not isinstance(origin, dict) or origin.get('type') not in ('annotation', 'vocal_yin'):
        invalid()
    if not isinstance(origin.get('citation' if origin['type'] == 'annotation' else 'fingerprint'), str) or not origin.get('citation' if origin['type'] == 'annotation' else 'fingerprint', '').strip():
        invalid()
    notes = value.get('notes')
    if not isinstance(notes, list) or len(notes) > 100000:
        invalid()
    previous_end = 0
    normalized = []
    for note in notes:
        if not isinstance(note, dict):
            invalid()
        start, end, midi, confidence = (note.get(key) for key in ('start_ms', 'end_ms', 'midi_note', 'confidence'))
        if type(start) is not int or type(end) is not int or not previous_end <= start < end <= duration_ms:
            invalid()
        if type(midi) not in (int, float) or not math.isfinite(midi) or not 0 <= midi <= 127:
            invalid()
        if type(confidence) not in (int, float) or not math.isfinite(confidence) or not 0 <= confidence <= 1:
            invalid()
        normalized.append(dict(start_ms=start, end_ms=end, midi_note=midi, confidence=confidence))
        previous_end = end
    return {'schema_version': 1, 'origin': origin, 'notes': normalized}
