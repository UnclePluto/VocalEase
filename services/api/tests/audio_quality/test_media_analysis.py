import io
import math
import struct
import wave

from vocaease_api.media_analysis import generate_spectrogram, waveform_envelope


def sine_wav(seconds: int = 1) -> bytes:
    output = io.BytesIO()
    with wave.open(output, "wb") as audio:
        audio.setnchannels(1)
        audio.setsampwidth(2)
        audio.setframerate(48_000)
        samples = [
            round(12_000 * math.sin(2 * math.pi * 440 * index / 48_000))
            for index in range(48_000 * seconds)
        ]
        audio.writeframes(b"".join(struct.pack("<h", sample) for sample in samples))
    return output.getvalue()


def test_waveform_envelope_is_derived_from_real_pcm(tmp_path):
    path = tmp_path / "voice.wav"
    path.write_bytes(sine_wav())

    points = waveform_envelope(path, max_points=100)

    assert 90 <= len(points) <= 100
    assert points[0]["start_ms"] == 0
    assert all(-1 <= point["min"] <= point["max"] <= 1 for point in points)
    assert max(point["max"] for point in points) > 0.3
    assert min(point["min"] for point in points) < -0.3
    assert all(point["rms"] > 0 for point in points)


def test_spectrogram_is_a_real_png_generated_from_audio(tmp_path):
    source = tmp_path / "voice.wav"
    target = tmp_path / "spectrogram.png"
    source.write_bytes(sine_wav())

    generate_spectrogram(source, target)

    assert target.read_bytes().startswith(b"\x89PNG\r\n\x1a\n")
    assert target.stat().st_size > 1_000
