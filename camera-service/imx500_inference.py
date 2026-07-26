"""
Inferência on-sensor Sony IMX500 via rpicam-apps (sem Picamera2).

Usa janela finita (default 8 s):
  rpicam-still --nopreview -t 8000 --post-process-file ... -o /tmp/ai.jpg -vv

Importante: a câmera deve estar livre (sem rpicam-vid) antes do still com post-process.
"""

from __future__ import annotations

import json
import logging
import os
import re
import shutil
import subprocess
import tempfile
import threading
import time
from pathlib import Path
from typing import Optional

import config
from model_manager import Detection

logger = logging.getLogger("camera-service.imx500-infer")

_lock = threading.Lock()

# Formato real do rpicam-apps (LOG level 2):
#   [0] : 003509[0] (0.91) @ 40,50 120x80
_DET_LINE_RE = re.compile(
    r"\[\d+\]\s*:\s*(?P<name>.+?)\[(?P<cat>\d+)\]\s+\((?P<conf>[0-9]*\.?[0-9]+)\)\s+@"
    r"\s+(?P<x>\d+),(?P<y>\d+)\s+(?P<w>\d+)x(?P<h>\d+)"
)

# Fallback sem o prefixo "[i] :"
_DET_PLAIN_RE = re.compile(
    r"(?P<name>.+?)\[(?P<cat>\d+)\]\s+\((?P<conf>[0-9]*\.?[0-9]+)\)\s+@"
    r"\s+(?P<x>\d+),(?P<y>\d+)\s+(?P<w>\d+)x(?P<h>\d+)"
)


def detect_live(
    rpk_path: Path,
    labels: list[str],
    threshold: float = 0.45,
    max_frames: int = 8,
    settle_ms: int = 400,
) -> list[Detection]:
    del max_frames, settle_ms
    if not rpk_path.is_file():
        raise FileNotFoundError(f"RPK não encontrado: {rpk_path}")

    still = _resolve_still()
    if not still:
        raise RuntimeError(
            "rpicam-still não encontrado. Instale rpicam-apps no Raspberry Pi "
            "(sudo apt install -y rpicam-apps imx500-all)."
        )

    with _lock:
        last_error: Optional[Exception] = None
        for attempt in range(2):
            try:
                _release_preview_streams()
                # Sensor / pipeline precisam liberar após rpicam-vid.
                time.sleep(0.9 if attempt == 0 else 1.6)
                return _detect_with_rpicam(still, rpk_path, labels, threshold)
            except Exception as exc:
                last_error = exc
                msg = str(exc).lower()
                retryable = (
                    "busy" in msg
                    or "in use" in msg
                    or "device" in msg
                    or "post-process" in msg
                    or "pipeline" in msg
                    or "failed to" in msg
                )
                logger.warning(
                    "rpicam IA tentativa %s falhou (%s)%s",
                    attempt + 1,
                    exc,
                    " — retry" if retryable and attempt == 0 else "",
                )
                if not retryable or attempt > 0:
                    raise
        if last_error:
            raise last_error
        return []


def _resolve_still() -> Optional[str]:
    for name in ("rpicam-still", "libcamera-still"):
        path = shutil.which(name)
        if path:
            return path
    return None


def _release_preview_streams() -> None:
    """Encerra streams de preview que bloqueiam o post-process IMX500."""
    for pattern in ("rpicam-vid", "libcamera-vid"):
        try:
            subprocess.run(
                ["pkill", "-f", pattern],
                capture_output=True,
                text=True,
                timeout=3,
                check=False,
            )
        except Exception:
            pass


def _detect_with_rpicam(
    still_cmd: str,
    rpk_path: Path,
    labels: list[str],
    threshold: float,
) -> list[Detection]:
    post_json = _write_post_process_json(rpk_path, labels, threshold)
    out_jpg = Path(tempfile.gettempdir()) / "rfidsdk_imx500_detect.jpg"
    log_path = config.MODEL_DIR / "last_rpicam_ai.log"
    capture_ms = max(
        2500,
        int(float(os.environ.get("CAMERA_IMX500_CAPTURE_MS", "8000"))),
    )
    timeout_s = max(
        10,
        int(float(os.environ.get("CAMERA_IMX500_TIMEOUT_S", "25"))),
    )

    cmd = [
        still_cmd,
        "--nopreview",
        "-t",
        str(capture_ms),
        "--post-process-file",
        str(post_json),
        "-o",
        str(out_jpg),
        "-v",
        "-v",
    ]
    logger.info("rpicam IA: %s", " ".join(cmd))
    try:
        completed = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            timeout=timeout_s,
            check=False,
        )
    except subprocess.TimeoutExpired as exc:
        raise RuntimeError(
            f"Timeout ({timeout_s}s) no rpicam-still com IMX500. "
            f"A captura deveria terminar em {capture_ms}ms."
        ) from exc

    combined = (completed.stdout or "") + "\n" + (completed.stderr or "")
    try:
        config.MODEL_DIR.mkdir(parents=True, exist_ok=True)
        log_path.write_text(combined, encoding="utf-8", errors="replace")
    except Exception:
        pass

    detections = _parse_detection_log(combined, labels)

    if completed.returncode != 0 and not detections:
        err = combined.strip() or f"exit={completed.returncode}"
        raise RuntimeError(_classify_rpicam_error(err))

    if not detections:
        logger.warning(
            "rpicam IA sem detecções parseadas (exit=%s). Veja %s",
            completed.returncode,
            log_path,
        )
    else:
        logger.info("rpicam IA: %d código(s) único(s)", len(detections))
    return detections


def _classify_rpicam_error(err: str) -> str:
    lower = (err or "").lower()
    # Logs verbose sempre citam "post-process"; só trate falha real.
    hard_fail = (
        "failed to create" in lower
        or "failed to configure" in lower
        or "no such file" in lower
        or "not found" in lower
        or "unavailable" in lower
        or "device or resource busy" in lower
        or "device busy" in lower
        or "already in use" in lower
        or "could not" in lower and "post" in lower
    )
    if hard_fail and ("post" in lower or "imx500" in lower or "busy" in lower):
        return (
            "Câmera ocupada ou post-process IMX500 indisponível. "
            "Pare o vídeo ao vivo e tente de novo. "
            "Se persistir: sudo apt install -y imx500-all rpicam-apps\n"
            f"Detalhe: {err[:400]}"
        )
    return f"rpicam-still IA falhou: {err[:500]}"


def _write_post_process_json(
    rpk_path: Path,
    labels: list[str],
    threshold: float,
) -> Path:
    config.MODEL_DIR.mkdir(parents=True, exist_ok=True)
    path = config.MODEL_DIR / "rpicam_imx500_detect.json"
    classes = [lab for lab in labels if lab and lab.strip()]
    if not classes:
        classes = ["object"]

    payload = {
        "imx500_object_detection": {
            "max_detections": 10,
            "threshold": float(threshold),
            "network_file": str(rpk_path.resolve()),
            "classes": classes,
        },
        "object_detect_draw_cv": {
            "line_thickness": 2,
        },
    }
    path.write_text(json.dumps(payload, indent=2), encoding="utf-8")
    return path


def _parse_detection_log(text: str, labels: list[str]) -> list[Detection]:
    """Uma entrada por código/label — mantém a maior confiança."""
    best: dict[str, Detection] = {}
    for raw in (text or "").splitlines():
        line = raw.strip()
        if "@" not in line or "[" not in line or "(" not in line:
            continue
        m = _DET_LINE_RE.search(line)
        if not m:
            m = _DET_PLAIN_RE.search(line)
        if not m:
            continue
        name = m.group("name").strip()
        if ":" in name:
            name = name.split(":")[-1].strip()
        if name.endswith("]") or not name:
            continue
        conf = float(m.group("conf"))
        x = int(m.group("x"))
        y = int(m.group("y"))
        w = int(m.group("w"))
        h = int(m.group("h"))
        if max(x + w, y + h) > 2:
            nw, nh = 640.0, 480.0
            box = (
                max(0.0, x / nw),
                max(0.0, y / nh),
                min(1.0, (x + w) / nw),
                min(1.0, (y + h) / nh),
            )
        else:
            box = (float(x), float(y), float(x + w), float(y + h))
        label = name
        try:
            cat = int(m.group("cat"))
            if 0 <= cat < len(labels) and labels[cat]:
                label = labels[cat]
        except Exception:
            pass
        key = (label or "").strip().upper()
        if not key:
            continue
        prev = best.get(key)
        if prev is None or conf > prev.confidence:
            best[key] = Detection(label=label, confidence=conf, box=box)
    return list(best.values())
