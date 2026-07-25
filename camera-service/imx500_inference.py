"""
Inferência on-sensor Sony IMX500 via rpicam-apps (sem Picamera2).

Alinha com o comando que funciona no terminal:
  rpicam-still --nopreview -t 0 --post-process-file ... -o /tmp/ai.jpg -v

Diferenças que quebravam a app:
- --immediate + timeout curto → captura antes do tensor da IMX500
- parser não lia o formato real do log: "[0] : nome[cat] (conf) @ x,y wxh"
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
        return _detect_with_rpicam(still, rpk_path, labels, threshold)


def _resolve_still() -> Optional[str]:
    for name in ("rpicam-still", "libcamera-still"):
        path = shutil.which(name)
        if path:
            return path
    return None


def _detect_with_rpicam(
    still_cmd: str,
    rpk_path: Path,
    labels: list[str],
    threshold: float,
) -> list[Detection]:
    post_json = _write_post_process_json(rpk_path, labels, threshold)
    out_jpg = Path(tempfile.gettempdir()) / "rfidsdk_imx500_detect.jpg"
    log_path = config.MODEL_DIR / "last_rpicam_ai.log"
    # Igual ao comando manual que funciona: -t 0, sem --immediate.
    # -vv para LOG(2) imprimir "[i] : name[cat] (conf) @ ..."
    timeout_s = int(float(os.environ.get("CAMERA_IMX500_TIMEOUT_S", "180")))
    shutter_ms = os.environ.get("CAMERA_IMX500_STILL_TIMEOUT_MS", "0")

    cmd = [
        still_cmd,
        "--nopreview",
        "-t",
        str(shutter_ms),
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
            "Na 1ª vez o firmware pode demorar; tente de novo."
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
        lower = err.lower()
        if "post" in lower and ("process" in lower or "stage" in lower):
            raise RuntimeError(
                "Post-process IMX500 indisponível no rpicam-apps. "
                "Instale: sudo apt install -y imx500-all rpicam-apps\n"
                f"Detalhe: {err[:400]}"
            )
        raise RuntimeError(f"rpicam-still IA falhou: {err[:500]}")

    if not detections:
        logger.warning(
            "rpicam IA sem detecções parseadas (exit=%s). Veja %s",
            completed.returncode,
            log_path,
        )
    else:
        logger.info("rpicam IA: %d detecção(ões)", len(detections))
    return detections


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

    # threshold um pouco mais baixo que o default 0.6 do exemplo oficial,
    # alinhado ao config.DETECTION_THRESHOLD da app.
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
    found: list[Detection] = []
    seen = set()
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
        # Prefixo residual tipo "0] : 003509" no fallback plain
        if ":" in name:
            name = name.split(":")[-1].strip()
        if name.endswith("]"):
            continue
        if not name:
            continue
        conf = float(m.group("conf"))
        x = int(m.group("x"))
        y = int(m.group("y"))
        w = int(m.group("w"))
        h = int(m.group("h"))
        # Still sem --width/--height fixos: coords já vêm no espaço ISP.
        # Normaliza com heurística; se > 1.5 trata como pixels em 640x480.
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
        key = (name, round(conf, 3), round(box[0], 3), round(box[1], 3))
        if key in seen:
            continue
        seen.add(key)
        label = name
        try:
            cat = int(m.group("cat"))
            if 0 <= cat < len(labels) and labels[cat]:
                label = labels[cat]
        except Exception:
            pass
        found.append(Detection(label=label, confidence=conf, box=box))
    return found
