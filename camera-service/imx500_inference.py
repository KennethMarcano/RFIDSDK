"""
Inferência on-sensor Sony IMX500 via rpicam-apps (sem Picamera2).

Fluxo:
1. Gera JSON de post-process apontando para network.rpk + labels
2. Roda rpicam-still --post-process-file ...
3. Lê as detecções no log verboso (Detection::toString)
"""

from __future__ import annotations

import json
import logging
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

# person[0] (0.87) @ 10,20 100x200
_DET_RE = re.compile(
    r"^(?P<name>.+?)\[(?P<cat>\d+)\]\s+\((?P<conf>[0-9]*\.?[0-9]+)\)\s+@"
    r"\s+(?P<x>\d+),(?P<y>\d+)\s+(?P<w>\d+)x(?P<h>\d+)\s*$"
)


def detect_live(
    rpk_path: Path,
    labels: list[str],
    threshold: float = 0.45,
    max_frames: int = 8,
    settle_ms: int = 400,
) -> list[Detection]:
    """Compat: max_frames/settle_ms ignorados — rpicam-still faz um disparo com IA."""
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
    # Timeout generoso: 1ª carga do firmware da IMX500 pode demorar minutos.
    timeout_s = int(float(__import__("os").environ.get("CAMERA_IMX500_TIMEOUT_S", "180")))

    cmd = [
        still_cmd,
        "--nopreview",
        "--immediate",
        "--timeout",
        "2500",
        "--width",
        "640",
        "--height",
        "480",
        "--post-process-file",
        str(post_json),
        "-o",
        str(out_jpg),
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
    detections = _parse_detection_log(combined, labels)

    if completed.returncode != 0 and not detections:
        err = combined.strip() or f"exit={completed.returncode}"
        # Mensagem útil se o estágio pós-processo não estiver instalado
        lower = err.lower()
        if "post" in lower and ("process" in lower or "stage" in lower):
            raise RuntimeError(
                "Post-process IMX500 indisponível no rpicam-apps. "
                "Instale: sudo apt install -y imx500-all rpicam-apps\n"
                f"Detalhe: {err[:400]}"
            )
        raise RuntimeError(f"rpicam-still IA falhou: {err[:500]}")

    if not detections:
        logger.info(
            "rpicam IA sem detecções (exit=%s). Trecho log: %s",
            completed.returncode,
            combined[-500:].replace("\n", " | "),
        )
    return detections


def _write_post_process_json(
    rpk_path: Path,
    labels: list[str],
    threshold: float,
) -> Path:
    """JSON no estilo /usr/share/rpi-camera-assets/imx500_mobilenet_ssd.json."""
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
            # Sem temporal_filter: still de um tiro precisa resposta imediata
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
        # Pode vir prefixado por timestamp/nível do logger
        if "] (" in line and " @ " in line and "[" in line:
            # tenta achar o trecho name[n] (conf) @ ...
            m = _DET_RE.search(line)
            if not m:
                # remove prefixos até o início do nome
                idx = line.find("[")
                if idx > 0:
                    # volta ao início do token do nome
                    start = line.rfind(" ", 0, idx)
                    candidate = line[start + 1 :] if start >= 0 else line
                    m = _DET_RE.match(candidate)
            if not m:
                continue
            name = m.group("name").strip()
            conf = float(m.group("conf"))
            x = int(m.group("x"))
            y = int(m.group("y"))
            w = int(m.group("w"))
            h = int(m.group("h"))
            # Normaliza box para 0..1 assumindo still 640x480 (fallback)
            nw, nh = 640.0, 480.0
            box = (
                max(0.0, x / nw),
                max(0.0, y / nh),
                min(1.0, (x + w) / nw),
                min(1.0, (y + h) / nh),
            )
            key = (name, round(conf, 3), round(box[0], 3), round(box[1], 3))
            if key in seen:
                continue
            seen.add(key)
            # Prefere label canônico da lista se bater por índice/nome
            label = name
            try:
                cat = int(m.group("cat"))
                if 0 <= cat < len(labels) and labels[cat]:
                    label = labels[cat]
            except Exception:
                pass
            found.append(Detection(label=label, confidence=conf, box=box))
    return found
