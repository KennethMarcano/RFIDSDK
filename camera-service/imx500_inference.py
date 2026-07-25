"""
Inferência on-sensor Sony IMX500 via Picamera2 + network.rpk.

O model_imx.onnx do conversor Sony contém ops custom (mct_quantizers) que o
ONNX Runtime NÃO executa. A via correta é empacotar packerOut.zip → network.rpk
e rodar no chip da câmera.
"""

from __future__ import annotations

import logging
import threading
import time
from pathlib import Path
from typing import Any, Optional

import config
from model_manager import Detection

logger = logging.getLogger("camera-service.imx500-infer")

_lock = threading.Lock()


def detect_live(
    rpk_path: Path,
    labels: list[str],
    threshold: float = 0.45,
    max_frames: int = 8,
    settle_ms: int = 400,
) -> list[Detection]:
    """
    Abre a IMX500 com o RPK, lê alguns frames de metadados, devolve detecções
    e libera a câmera (para o Java voltar a usar rpicam-vid/still).
    """
    if not rpk_path.is_file():
        raise FileNotFoundError(f"RPK não encontrado: {rpk_path}")

    try:
        from picamera2 import Picamera2
        from picamera2.devices.imx500 import IMX500, NetworkIntrinsics
    except ImportError as exc:
        raise RuntimeError(
            "picamera2 / IMX500 não disponível. No Raspberry Pi: "
            "sudo apt install -y python3-picamera2 imx500-all"
        ) from exc

    with _lock:
        return _detect_locked(
            rpk_path=rpk_path,
            labels=labels,
            threshold=threshold,
            max_frames=max_frames,
            settle_ms=settle_ms,
            Picamera2=Picamera2,
            IMX500=IMX500,
            NetworkIntrinsics=NetworkIntrinsics,
        )


def _detect_locked(
    rpk_path: Path,
    labels: list[str],
    threshold: float,
    max_frames: int,
    settle_ms: int,
    Picamera2: Any,
    IMX500: Any,
    NetworkIntrinsics: Any,
) -> list[Detection]:
    imx500 = IMX500(str(rpk_path))
    intrinsics = imx500.network_intrinsics
    if not intrinsics:
        intrinsics = NetworkIntrinsics()
        intrinsics.task = "object detection"
    if labels:
        intrinsics.labels = list(labels)
    try:
        intrinsics.update_with_defaults()
    except Exception:
        pass

    picam2 = Picamera2(imx500.camera_num)
    try:
        cam_config = picam2.create_preview_configuration(
            controls={"FrameRate": getattr(intrinsics, "inference_rate", 30) or 30},
            buffer_count=4,
        )
        try:
            imx500.show_network_fw_progress_bar()
        except Exception:
            pass
        picam2.start(cam_config, show_preview=False)
        if settle_ms > 0:
            time.sleep(settle_ms / 1000.0)

        best: list[Detection] = []
        for _ in range(max(1, max_frames)):
            metadata = picam2.capture_metadata()
            parsed = _parse_imx500_outputs(imx500, intrinsics, metadata, labels, threshold)
            if len(parsed) >= len(best):
                best = parsed
            if best:
                # Já temos algo — não precisa esgotar todos os frames
                break
            time.sleep(0.05)
        return best
    finally:
        try:
            picam2.stop()
        except Exception:
            pass
        try:
            picam2.close()
        except Exception:
            pass


def _parse_imx500_outputs(
    imx500: Any,
    intrinsics: Any,
    metadata: dict,
    labels: list[str],
    threshold: float,
) -> list[Detection]:
    import numpy as np

    try:
        np_outputs = imx500.get_outputs(metadata, add_batch=True)
    except Exception as exc:
        logger.debug("get_outputs falhou: %s", exc)
        return []
    if np_outputs is None:
        return []

    boxes = scores = classes = None
    try:
        # Formato comum SSD/FPN no IMX500 demo
        if len(np_outputs) >= 3:
            boxes = np.asarray(np_outputs[0][0] if np_outputs[0].ndim > 2 else np_outputs[0])
            scores = np.asarray(np_outputs[1][0] if np_outputs[1].ndim > 1 else np_outputs[1])
            classes = np.asarray(np_outputs[2][0] if np_outputs[2].ndim > 1 else np_outputs[2])
    except Exception:
        boxes = scores = classes = None

    if boxes is None or scores is None or classes is None:
        # Fallback genérico: procura tensores 2D Nx4 + 1D
        arrays = [np.asarray(o) for o in np_outputs]
        for a in arrays:
            s = np.squeeze(a)
            if s.ndim == 2 and s.shape[-1] == 4:
                boxes = s
            elif s.ndim == 1 and s.dtype.kind in "fc" and (scores is None):
                scores = s
            elif s.ndim == 1 and classes is None:
                classes = s.astype(int)

    if boxes is None or scores is None or classes is None:
        return []

    input_h = input_w = 640
    try:
        input_w, input_h = imx500.get_input_size()
    except Exception:
        pass

    bbox_norm = bool(getattr(intrinsics, "bbox_normalization", False))
    bbox_order = getattr(intrinsics, "bbox_order", "yx") or "yx"

    detections: list[Detection] = []
    n = min(len(scores), len(classes), len(boxes))
    for i in range(n):
        score = float(scores[i])
        if score < threshold:
            continue
        cls_id = int(classes[i])
        label = labels[cls_id] if 0 <= cls_id < len(labels) else str(cls_id)
        box = np.asarray(boxes[i], dtype=float).reshape(-1)
        if box.size < 4:
            continue
        y0, x0, y1, x1 = box[0], box[1], box[2], box[3]
        if bbox_order == "xy":
            x0, y0, x1, y1 = box[0], box[1], box[2], box[3]
        if bbox_norm:
            # já 0..1 relativo à entrada
            pass
        else:
            x0, x1 = x0 / float(input_w), x1 / float(input_w)
            y0, y1 = y0 / float(input_h), y1 / float(input_h)
        detections.append(
            Detection(
                label=label,
                confidence=score,
                box=(float(x0), float(y0), float(x1), float(y1)),
            )
        )
    return detections
