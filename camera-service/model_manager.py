"""
Gerenciador singleton do modelo IMX500.

Responsabilidades:
1. Empacotar packerOut.zip -> network.rpk (imx500-package) uma vez, se necessário
2. Carregar labels + sessão ONNX em memória no boot e manter até o shutdown
3. Inferência sobre a foto capturada (fallback IA do fluxo)
4. Expor status estável para /health e /camera/status

Não mantém Picamera2 aberto em idle (conflitaria com rpicam-still/vid da app Java).
O firmware .rpk fica pronto no disco para uso on-sensor quando necessário.
"""

from __future__ import annotations

import logging
import shutil
import subprocess
import threading
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Optional

import config

logger = logging.getLogger("camera-service.model")

_lock = threading.RLock()
_state: Optional["ModelState"] = None


@dataclass
class Detection:
    label: str
    confidence: float
    box: tuple[float, float, float, float] = (0.0, 0.0, 0.0, 0.0)  # x1,y1,x2,y2 norm 0..1


@dataclass
class ModelState:
    labels: list[str] = field(default_factory=list)
    onnx_path: Optional[Path] = None
    rpk_path: Optional[Path] = None
    session: Any = None  # onnxruntime.InferenceSession
    input_name: str = ""
    input_size: int = config.INPUT_SIZE
    loaded: bool = False
    rpk_ready: bool = False
    last_error: Optional[str] = None
    load_ms: int = 0
    backend: str = "none"  # onnx | stub | none


def get_state() -> ModelState:
    global _state
    with _lock:
        if _state is None:
            _state = ModelState()
        return _state


def status() -> dict:
    st = get_state()
    with _lock:
        return {
            "model_loaded": st.loaded,
            "rpk_ready": st.rpk_ready,
            "backend": st.backend,
            "labels": list(st.labels),
            "label_count": len(st.labels),
            "onnx_path": str(st.onnx_path) if st.onnx_path else None,
            "rpk_path": str(st.rpk_path) if st.rpk_path else None,
            "model_dir": str(config.MODEL_DIR),
            "input_size": st.input_size,
            "threshold": config.DETECTION_THRESHOLD,
            "load_ms": st.load_ms,
            "last_error": st.last_error,
        }


def ensure_loaded() -> ModelState:
    """Idempotente: carrega uma vez e reutiliza a sessão em memória."""
    st = get_state()
    with _lock:
        if st.loaded and st.session is not None:
            return st
        if st.loaded and st.backend == "stub":
            return st
    return load()


def load() -> ModelState:
    """Carrega artefatos do modelo. Seguro chamar mais de uma vez (re-load)."""
    global _state
    started = time.perf_counter()
    st = ModelState()
    try:
        config.MODEL_DIR.mkdir(parents=True, exist_ok=True)
        st.labels = _load_labels(config.model_labels_path())
        st.rpk_path, st.rpk_ready = _ensure_rpk()
        onnx = config.model_onnx_path()
        if not onnx.is_file():
            raise FileNotFoundError(f"ONNX não encontrado: {onnx}")

        try:
            import onnxruntime as ort  # type: ignore
        except ImportError as exc:
            if config.STUB_MODE:
                st.backend = "stub"
                st.loaded = True
                st.onnx_path = onnx
                st.last_error = (
                    "onnxruntime ausente — stub ativo (dev). "
                    "No Raspberry: pip install -r requirements.txt"
                )
                logger.warning(st.last_error)
            else:
                raise RuntimeError(
                    "onnxruntime não instalado. Execute: "
                    "pip install -r camera-service/requirements.txt"
                ) from exc
        else:
            opts = ort.SessionOptions()
            opts.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
            providers = _select_providers(ort)
            st.session = ort.InferenceSession(
                str(onnx), sess_options=opts, providers=providers
            )
            inputs = st.session.get_inputs()
            if not inputs:
                raise RuntimeError("Modelo ONNX sem inputs")
            st.input_name = inputs[0].name
            st.input_size = _infer_input_size(inputs[0].shape, config.INPUT_SIZE)
            st.onnx_path = onnx
            st.backend = "onnx"
            st.loaded = True
            # Warm-up: 1 inferência dummy para manter kernels prontos
            _warmup(st)
            logger.info(
                "Modelo ONNX carregado (%s), labels=%d, rpk=%s, providers=%s",
                onnx.name,
                len(st.labels),
                "ok" if st.rpk_ready else "pendente",
                providers,
            )

        st.load_ms = int((time.perf_counter() - started) * 1000)
        st.last_error = st.last_error  # preserve stub warning if any
    except Exception as exc:
        st.loaded = False
        st.backend = "none"
        st.last_error = str(exc)
        st.load_ms = int((time.perf_counter() - started) * 1000)
        logger.exception("Falha ao carregar modelo IMX500: %s", exc)

    with _lock:
        _state = st
    return st


def unload() -> None:
    global _state
    with _lock:
        if _state is not None:
            _state.session = None
            _state.loaded = False
            _state.backend = "none"
        _state = None


def detect(image_path: str, threshold: Optional[float] = None) -> list[Detection]:
    """Roda inferência na imagem e devolve detecções com labels do pedido."""
    st = ensure_loaded()
    if not st.loaded:
        raise RuntimeError(st.last_error or "Modelo não carregado")
    if st.backend == "stub" or st.session is None:
        return []

    thr = config.DETECTION_THRESHOLD if threshold is None else float(threshold)
    tensor = _preprocess_image(Path(image_path), st.input_size)
    outputs = st.session.run(None, {st.input_name: tensor})
    return _parse_outputs(outputs, st.labels, thr)


def _select_providers(ort_module) -> list[str]:
    available = ort_module.get_available_providers()
    preferred = ["CUDAExecutionProvider", "CPUExecutionProvider"]
    return [p for p in preferred if p in available] or ["CPUExecutionProvider"]


def _load_labels(path: Path) -> list[str]:
    if not path.is_file():
        logger.warning("labels.txt ausente em %s", path)
        return []
    lines = []
    for raw in path.read_text(encoding="utf-8").splitlines():
        label = raw.strip()
        if label and not label.startswith("#"):
            lines.append(label)
    return lines


def _ensure_rpk() -> tuple[Optional[Path], bool]:
    """Garante network.rpk a partir de packerOut.zip (ferramenta oficial Sony/RPi)."""
    rpk = config.model_rpk_path()
    if rpk.is_file() and rpk.stat().st_size > 0:
        return rpk, True

    packer = config.model_packer_zip_path()
    if not packer.is_file():
        logger.warning("packerOut.zip ausente — RPK não será gerado (%s)", packer)
        return None, False

    tool = shutil.which("imx500-package")
    if not tool:
        logger.warning(
            "imx500-package não encontrado no PATH. "
            "No Raspberry Pi: sudo apt install imx500-tools "
            "(ou pacote equivalente). O ONNX ainda funciona para o fallback."
        )
        return None, False

    out_dir = config.MODEL_DIR / "packaged"
    out_dir.mkdir(parents=True, exist_ok=True)
    try:
        completed = subprocess.run(
            [tool, "-i", str(packer), "-o", str(out_dir)],
            capture_output=True,
            text=True,
            timeout=300,
            check=False,
        )
        if completed.returncode != 0:
            logger.error(
                "imx500-package falhou: %s",
                (completed.stderr or completed.stdout or "").strip(),
            )
            return None, False
        produced = _find_rpk(out_dir)
        if produced is None:
            logger.error("imx500-package não gerou .rpk em %s", out_dir)
            return None, False
        # Cópia canônica ao lado dos demais artefatos
        shutil.copy2(produced, rpk)
        logger.info("RPK gerado: %s", rpk)
        return rpk, True
    except Exception as exc:
        logger.error("Erro ao empacotar RPK: %s", exc)
        return None, False


def _find_rpk(directory: Path) -> Optional[Path]:
    candidates = sorted(directory.rglob("*.rpk"))
    return candidates[0] if candidates else None


def _infer_input_size(shape, default: int) -> int:
    # formatos comuns: [1,3,H,W], [1,H,W,3], [3,H,W]
    try:
        dims = [int(d) for d in shape if isinstance(d, (int, float)) and int(d) > 0]
        for d in dims:
            if d in (320, 416, 512, 640, 1280):
                return int(d)
    except Exception:
        pass
    return default


def _warmup(st: ModelState) -> None:
    try:
        import numpy as np

        size = st.input_size
        # NCHW uint8 ou float — tentamos float32 normalizado
        dummy = np.zeros((1, 3, size, size), dtype=np.float32)
        st.session.run(None, {st.input_name: dummy})
    except Exception:
        # Segunda tentativa: NHWC
        try:
            import numpy as np

            size = st.input_size
            dummy = np.zeros((1, size, size, 3), dtype=np.float32)
            st.session.run(None, {st.input_name: dummy})
        except Exception as exc:
            logger.debug("Warm-up ONNX ignorado: %s", exc)


def _preprocess_image(path: Path, size: int):
    import numpy as np
    from PIL import Image

    if not path.is_file():
        raise FileNotFoundError(f"Imagem não encontrada: {path}")

    with Image.open(path) as img:
        img = img.convert("RGB")
        img = img.resize((size, size), Image.Resampling.BILINEAR)
        arr = np.asarray(img, dtype=np.float32)
    # dnnParams: scale 0.00390625 = 1/256
    arr = arr * (1.0 / 256.0)
    # NCHW
    arr = np.transpose(arr, (2, 0, 1))
    arr = np.expand_dims(arr, axis=0)
    return arr


def _parse_outputs(outputs: list, labels: list[str], threshold: float) -> list[Detection]:
    """Interpreta saídas pós-NMS (MultiClassNMS) ou tensores YOLO genéricos."""
    import numpy as np

    if not outputs:
        return []

    arrays = [np.asarray(o) for o in outputs]
    detections: list[Detection] = []

    # Caso típico IMX pack: boxes[N,4], scores[N], classes[N] (+ count opcional)
    boxes, scores, classes = _extract_nms_tensors(arrays)
    if boxes is not None and scores is not None and classes is not None:
        n = min(len(scores), len(classes), len(boxes))
        for i in range(n):
            score = float(scores[i])
            if score < threshold:
                continue
            cls_id = int(classes[i])
            label = labels[cls_id] if 0 <= cls_id < len(labels) else str(cls_id)
            box = _normalize_box(boxes[i])
            detections.append(Detection(label=label, confidence=score, box=box))
        return detections

    # Fallback: varredura de scores por classe se formato desconhecido
    for arr in arrays:
        flat = arr.reshape(-1)
        if flat.size == 0 or flat.size > 100_000:
            continue
        # Heurística: vetor de confidences
        if flat.dtype.kind in "fc" and flat.size <= 1000:
            for idx, score in enumerate(flat):
                s = float(score)
                if s < threshold:
                    continue
                label = labels[idx] if idx < len(labels) else str(idx)
                detections.append(Detection(label=label, confidence=s))
    return detections


def _extract_nms_tensors(arrays: list):
    import numpy as np

    boxes = scores = classes = None
    for arr in arrays:
        a = np.squeeze(arr)
        if a.ndim == 2 and a.shape[-1] == 4:
            boxes = a
        elif a.ndim == 1:
            # scores (float 0..1) vs classes (ints)
            if a.dtype.kind in "fc" and a.max(initial=0) <= 1.5:
                scores = a.astype(float)
            else:
                classes = a.astype(int)
        elif a.ndim == 0:
            continue
    # Se só temos 2 vetores 1D, assume scores + classes pela ordem
    one_d = [np.squeeze(a) for a in arrays if np.squeeze(a).ndim == 1]
    if scores is None and len(one_d) >= 1:
        scores = one_d[0].astype(float)
    if classes is None and len(one_d) >= 2:
        classes = one_d[1].astype(int)
    if boxes is None:
        for a in arrays:
            s = np.squeeze(a)
            if s.ndim == 2 and s.shape[0] in (300, 100, 84) and s.shape[1] == 4:
                boxes = s
                break
    if boxes is None:
        return None, None, None
    if scores is None or classes is None:
        return None, None, None
    return boxes, scores, classes


def _normalize_box(box) -> tuple[float, float, float, float]:
    import numpy as np

    b = np.asarray(box, dtype=float).reshape(-1)
    if b.size < 4:
        return (0.0, 0.0, 0.0, 0.0)
    x1, y1, x2, y2 = float(b[0]), float(b[1]), float(b[2]), float(b[3])
    # Se parece coordenadas em pixels 0..640, normaliza
    if max(abs(x1), abs(y1), abs(x2), abs(y2)) > 2.0:
        s = float(config.INPUT_SIZE)
        x1, y1, x2, y2 = x1 / s, y1 / s, x2 / s, y2 / s
    return (x1, y1, x2, y2)
