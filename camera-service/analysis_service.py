"""Análise por imagem — fallback IA usando o modelo IMX500 carregado em memória."""

from __future__ import annotations

import logging
from collections import Counter

import config
import model_manager

logger = logging.getLogger("camera-service.analysis")

_DEMO_MISSING = {"1001": {2: ["ABC123"]}}

# Cena / contexto — não são produtos do pedido (caixa de conferência, operador).
_IGNORED_SCENE_LABELS = frozenset({"CAIXA", "PESSOA"})

# Label antigo do modelo → código atual do pedido (mesmo produto renomeado).
_PRODUCT_CODE_ALIASES = {
    "003509": "003511",
}


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

    # Backend IMX500: a foto JPG é só registro; a IA roda no sensor.
    state = model_manager.get_state()
    backend_note = ""
    if state.backend == "imx500_rpk":
        backend_note = " (IMX500 on-sensor)"

    # Um código por produto: mantém só a maior confiança (com alias canônico).
    unique: dict[str, model_manager.Detection] = {}
    for det in detections:
        key = _canonical_product_code(det.label)
        if not key:
            continue
        prev = unique.get(key)
        if prev is None or float(det.confidence) > float(prev.confidence):
            unique[key] = det
    detections = list(unique.values())

    detected_counts = Counter(_canonical_product_code(d.label) for d in detections)

    detected_products = []
    for det in detections:
        code = _display_product_code(det.label)
        detected_products.append({
            "code": code,
            "name": code,
            "confidence": round(float(det.confidence), 4),
            "box": list(det.box),
        })

    expected_by_key: dict[str, dict] = {}
    missing: list[dict] = []
    for product in expected_products:
        code = str(product.get("code", "")).strip()
        display_code = _display_product_code(code)
        name = str(product.get("name") or code)
        if _normalize(name) == _normalize(code) or _normalize(name) in _PRODUCT_CODE_ALIASES:
            name = display_code
        else:
            name = _remap_codes_in_text(name)
        qty = max(1, int(product.get("quantity") or 1))
        key = _canonical_product_code(code)
        if key:
            expected_by_key[key] = {"code": display_code, "name": name, "quantity": qty}
        found = detected_counts.get(key, 0)
        # Também aceita match parcial (label contém código)
        if found < qty:
            found = max(found, _count_fuzzy(detected_counts, key))
        if found < qty:
            missing.append({
                "code": display_code,
                "name": name,
                "reason": (
                    f"Esperado {qty}, detectado {found} "
                    f"(threshold={config.DETECTION_THRESHOLD})."
                ),
            })

    # Produto detectado que não está no pedido = sobra / não pertence.
    # Ignora caixa e pessoa (podem aparecer na cena sem ser item do pedido).
    unexpected: list[dict] = []
    for det in detections:
        key = _canonical_product_code(det.label)
        if not key or _is_ignored_scene_label(key):
            continue
        if _matches_expected(key, expected_by_key):
            continue
        code = _display_product_code(det.label)
        unexpected.append({
            "code": code,
            "name": code,
            "confidence": round(float(det.confidence), 4),
            "reason": "Produto detectado que não pertence ao pedido.",
        })

    if missing and unexpected:
        message = _remap_codes_in_text(
            f"IA: faltando {', '.join(p['name'] for p in missing)}; "
            f"não pertencem ao pedido: {', '.join(p['name'] for p in unexpected)}"
            f"{backend_note}"
        )
    elif missing:
        names = ", ".join(p["name"] for p in missing)
        message = _remap_codes_in_text(f"IA: não identificado — {names}{backend_note}")
    elif unexpected:
        names = ", ".join(p["name"] for p in unexpected)
        message = _remap_codes_in_text(
            f"IA: produto(s) fora do pedido — {names}{backend_note}"
        )
    elif not expected_products:
        message = (
            f"IA: {len(detected_products)} detecção(ões){backend_note}"
        )
    else:
        message = (
            f"IA: todos os produtos esperados identificados "
            f"({len(expected_products)}){backend_note}."
        )
    return {
        "success": True,
        "detected_products": detected_products,
        "missing_products": missing,
        "unexpected_products": unexpected,
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
        display_code = _display_product_code(str(code))
        order = str(product.get("order_number", ""))
        volume = int(product.get("volume_index", 0))
        demo_missing = _DEMO_MISSING.get(order, {}).get(volume, [])
        if code in demo_missing or display_code in demo_missing:
            name = str(product.get("name", code))
            if _normalize(name) == _normalize(str(code)):
                name = display_code
            else:
                name = _remap_codes_in_text(name)
            missing.append({
                "code": display_code,
                "name": name,
                "reason": "Não identificado por imagem (stub demo).",
            })
    message = "Análise stub (sem onnxruntime)."
    if missing:
        names = ", ".join(p["name"] for p in missing)
        message = _remap_codes_in_text(f"Não identificado por imagem: {names}")
    return {
        "success": True,
        "detected_products": [],
        "missing_products": missing,
        "unexpected_products": [],
        "message": message,
    }


def _normalize(value: str) -> str:
    return (value or "").strip().upper()


def _canonical_product_code(value: str) -> str:
    """Normaliza e aplica alias (ex.: label 003509 → código de pedido 003511)."""
    key = _normalize(value)
    return _PRODUCT_CODE_ALIASES.get(key, key)


def _display_product_code(value: str) -> str:
    """Código a exibir nas mensagens/UI (sempre o código atual do pedido)."""
    raw = (value or "").strip()
    key = _normalize(raw)
    if not key:
        return raw
    return _PRODUCT_CODE_ALIASES.get(key, raw)


def _remap_codes_in_text(text: str) -> str:
    """Garante que mensagens nunca exponham o código antigo do modelo."""
    if not text:
        return text
    result = text
    for old, new in _PRODUCT_CODE_ALIASES.items():
        result = result.replace(old, new)
        result = result.replace(old.lower(), new)
    return result


def _is_ignored_scene_label(key: str) -> bool:
    """Caixa / pessoa (e variações) não contam como produto fora do pedido."""
    if not key:
        return False
    if key in _IGNORED_SCENE_LABELS:
        return True
    for ignored in _IGNORED_SCENE_LABELS:
        if ignored in key or key in ignored:
            return True
    return False


def _matches_expected(detected_key: str, expected_by_key: dict[str, dict]) -> bool:
    if not detected_key:
        return False
    detected = _canonical_product_code(detected_key)
    if detected in expected_by_key:
        return True
    for expected_key in expected_by_key:
        expected = _canonical_product_code(expected_key)
        if expected and (expected in detected or detected in expected):
            return True
    return False


def _count_fuzzy(counts: Counter, code: str) -> int:
    if not code:
        return 0
    target = _canonical_product_code(code)
    total = 0
    for label, n in counts.items():
        key = _canonical_product_code(label)
        if target in key or key in target:
            total += n
    return total


def _error(msg: str) -> dict:
    return {
        "success": False,
        "detected_products": [],
        "missing_products": [],
        "unexpected_products": [],
        "message": msg,
    }
