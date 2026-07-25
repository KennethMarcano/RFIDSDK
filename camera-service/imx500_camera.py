"""Wrapper Sony IMX500 — captura via rpicam + status do modelo carregado."""

from __future__ import annotations

import logging
import shutil
import subprocess
from pathlib import Path
from typing import Optional

import config
import model_manager

logger = logging.getLogger("camera-service.imx500")

_ready = False
_last_error = None  # type: Optional[str]
_camera_info = ""
_probed = False


def _resolve_cmd(names):
    for name in names:
        path = shutil.which(name)
        if path:
            return path
    return None


def _list_cameras():
    cmd = _resolve_cmd(["rpicam-hello", "libcamera-hello"])
    if not cmd:
        return False, "rpicam-hello não encontrado no PATH"
    try:
        completed = subprocess.run(
            [cmd, "--list-cameras"],
            capture_output=True,
            text=True,
            timeout=12,
            check=False,
        )
        output = (completed.stdout or "") + (completed.stderr or "")
        lower = output.lower()
        if "no cameras available" in lower or "no camera available" in lower:
            return False, output.strip() or "Nenhuma câmera disponível"
        present = (
            "available cameras" in lower
            or "imx500" in lower
            or "/base/" in lower
            or completed.returncode == 0
        )
        return present, output.strip() or ("OK" if present else "Câmera não detectada")
    except Exception as exc:
        return False, str(exc)


def probe():
    """Atualiza estado da câmera (hardware real ou stub)."""
    global _ready, _last_error, _camera_info, _probed
    if config.STUB_MODE:
        _ready = True
        _last_error = None
        _camera_info = "stub_mode"
        _probed = True
        return
    present, info = _list_cameras()
    _camera_info = info
    _ready = present
    _last_error = None if present else info
    _probed = True


def status():
    if not _probed:
        probe()
    model = model_manager.status()
    return {
        "ready": _ready,
        "model_loaded": bool(model.get("model_loaded")),
        "rpk_ready": bool(model.get("rpk_ready")),
        "model_backend": model.get("backend"),
        "model_dir": model.get("model_dir"),
        "onnx_path": model.get("onnx_path"),
        "rpk_path": model.get("rpk_path"),
        "labels": model.get("labels") or [],
        "stub_mode": config.STUB_MODE,
        "last_error": _last_error or model.get("last_error"),
        "camera_info": _camera_info,
        "imx500": "imx500" in (_camera_info or "").lower(),
        "model_load_ms": model.get("load_ms"),
    }


def recalibrate():
    global _ready, _last_error
    if config.STUB_MODE:
        _ready = True
        _last_error = None
        # Reafirma modelo em memória
        model_manager.ensure_loaded()
        return True, "Recalibração simulada — modelo mantido em memória."
    try:
        probe()
        if not _ready:
            return False, _last_error or "Câmera não detectada"
        state = model_manager.ensure_loaded()
        _last_error = None
        extra = "modelo OK" if state.loaded else f"modelo: {state.last_error}"
        rpk = "RPK pronto" if state.rpk_ready else "RPK pendente"
        return True, f"Câmera verificada / pronta (IMX500). {extra}; {rpk}."
    except Exception as exc:
        _last_error = str(exc)
        _ready = False
        return False, str(exc)


def capture(output_path):
    global _last_error
    path = Path(output_path)
    path.parent.mkdir(parents=True, exist_ok=True)

    if config.STUB_MODE:
        try:
            _write_stub_png(path)
            _last_error = None
            resolved = str(path.resolve())
            return True, resolved, "Foto capturada (stub)."
        except Exception as exc:
            _last_error = str(exc)
            return False, "", str(exc)

    still = _resolve_cmd(["rpicam-still", "libcamera-still"])
    if not still:
        _last_error = "rpicam-still não encontrado"
        return False, "", _last_error

    suffix = path.suffix.lower()
    target = path if suffix in (".png", ".jpg", ".jpeg") else path.with_suffix(".jpg")
    cmd = [
        still,
        "--nopreview",
        "--immediate",
        "--timeout",
        "2000",
        "-o",
        str(target),
    ]
    try:
        completed = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            timeout=30,
            check=False,
        )
        if completed.returncode != 0 or not target.is_file() or target.stat().st_size == 0:
            err = (completed.stderr or completed.stdout or "captura falhou").strip()
            _last_error = err
            return False, "", err
        _last_error = None
        return True, str(target.resolve()), "Foto capturada (rpicam-still)."
    except Exception as exc:
        _last_error = str(exc)
        return False, "", str(exc)


def _write_stub_png(path):
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
