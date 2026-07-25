"""Análise por imagem — fallback IA usando o modelo IMX500 carregado em memória."""

from __future__ import annotations

import logging
from collections import Counter

import config
import model_manager

logger = logging.getLogger("camera-service.analysis")

_DEMO_MISSING = {"1001": {2: ["ABC123"]}}


def analyze(image_path: str, expected_products: list[dict]) -> dict:
    if not image_path:
        return _error("Caminho da imagem não informado.")

    try:
        state = model_manager.ensure_loaded()
    except Exception as exc:
        return _error(f"Modelo indisponível: {exc}")

    if not state.loaded:
        return _error(state.last_error or "Modelo IMX500 não carregado.")

    # Dev sem onnxruntime: mantém comportamento demo
    if state.backend == "stub" and config.STUB_MODE:
        return _analyze_stub(expected_products)

    try:
        detections = model_manager.detect(image_path)
    except Exception as exc:
        logger.exception("Inferência falhou")
        return _error(f"Falha na inferência: {exc}")

    detected_labels = [d.label for d in detections]
    detected_counts = Counter(_normalize(label) for label in detected_labels)

    detected_products = []
    for det in detections:
        detected_products.append({
            "code": det.label,
            "name": det.label,
            "confidence": round(float(det.confidence), 4),
            "box": list(det.box),
        })

    missing: list[dict] = []
    for product in expected_products:
        code = str(product.get("code", "")).strip()
        name = str(product.get("name") or code)
        qty = max(1, int(product.get("quantity") or 1))
        key = _normalize(code)
        found = detected_counts.get(key, 0)
        # Também aceita match parcial (label contém código)
        if found < qty:
            found = max(found, _count_fuzzy(detected_counts, key))
        if found < qty:
            missing.append({
                "code": code,
                "name": name,
                "reason": (
                    f"Esperado {qty}, detectado {found} "
                    f"(threshold={config.DETECTION_THRESHOLD})."
                ),
            })

    if missing:
        names = ", ".join(p["name"] for p in missing)
        message = f"IA: não identificado — {names}"
    elif not expected_products:
        message = (
            f"IA: {len(detected_products)} detecção(ões) "
            f"(nenhum produto esperado informado)."
        )
    else:
        message = f"IA: todos os produtos esperados identificados ({len(expected_products)})."

    return {
        "success": True,
        "detected_products": detected_products,
        "missing_products": missing,
        "message": message,
        "model": {
            "backend": state.backend,
            "rpk_ready": state.rpk_ready,
            "labels": state.labels,
        },
    }


def _analyze_stub(expected_products: list[dict]) -> dict:
    missing: list[dict] = []
    for product in expected_products:
        code = product.get("code", "")
        order = str(product.get("order_number", ""))
        volume = int(product.get("volume_index", 0))
        demo_missing = _DEMO_MISSING.get(order, {}).get(volume, [])
        if code in demo_missing:
            missing.append({
                "code": code,
                "name": product.get("name", code),
                "reason": "Não identificado por imagem (stub demo).",
            })
    message = "Análise stub (sem onnxruntime)."
    if missing:
        names = ", ".join(p["name"] for p in missing)
        message = f"Não identificado por imagem: {names}"
    return {
        "success": True,
        "detected_products": [],
        "missing_products": missing,
        "message": message,
    }


def _normalize(value: str) -> str:
    return (value or "").strip().upper()


def _count_fuzzy(counts: Counter, code: str) -> int:
    if not code:
        return 0
    total = 0
    for label, n in counts.items():
        if code in label or label in code:
            total += n
    return total


def _error(msg: str) -> dict:
    return {
        "success": False,
        "detected_products": [],
        "missing_products": [],
        "message": msg,
    }
