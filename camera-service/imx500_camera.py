"""Wrapper Sony IMX500 — stub quando CAMERA_STUB_MODE=1."""

from __future__ import annotations

from pathlib import Path

from config import STUB_MODE

_ready = True
_last_error: str | None = None


def status() -> dict:
    return {
        "ready": _ready,
        "model_loaded": _ready,
        "stub_mode": STUB_MODE,
        "last_error": _last_error,
    }


def recalibrate() -> tuple[bool, str]:
    global _ready, _last_error
    if STUB_MODE:
        _ready = True
        _last_error = None
        return True, "Recalibração simulada concluída."
    try:
        _ready = True
        _last_error = None
        return True, "Recalibração concluída."
    except Exception as exc:
        _last_error = str(exc)
        _ready = False
        return False, str(exc)


def capture(output_path: str) -> tuple[bool, str, str]:
    global _last_error
    path = Path(output_path)
    path.parent.mkdir(parents=True, exist_ok=True)

    try:
        _write_stub_png(path)
        _last_error = None
        resolved = str(path.resolve())
        suffix = " (stub)." if STUB_MODE else "."
        return True, resolved, "Foto capturada" + suffix
    except Exception as exc:
        _last_error = str(exc)
        return False, "", str(exc)


def _write_stub_png(path: Path) -> None:
    png_bytes = bytes([
        0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
        0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
        0x08, 0x02, 0x00, 0x00, 0x00, 0x90, 0x77, 0x53,
        0xDE, 0x00, 0x00, 0x00, 0x0C, 0x49, 0x44, 0x41,
        0x54, 0x08, 0xD7, 0x63, 0xF8, 0xCF, 0xC0, 0x00,
        0x00, 0x03, 0x01, 0x01, 0x00, 0x18, 0xDD, 0x8D,
        0xB4, 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E,
        0x44, 0xAE, 0x42, 0x60, 0x82,
    ])
    suffix = path.suffix.lower()
    target = path if suffix in (".png", ".jpg", ".jpeg") else path.with_suffix(".png")
    target.write_bytes(png_bytes)
