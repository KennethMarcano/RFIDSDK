"""Análise por imagem — fallback; stub retorna faltantes simulados."""

from __future__ import annotations

from config import STUB_MODE

_DEMO_MISSING = {"1001": {2: ["ABC123"]}}


def analyze(image_path: str, expected_products: list[dict]) -> dict:
    if not image_path:
        return _error("Caminho da imagem não informado.")

    missing: list[dict] = []
    message = "Análise concluída."

    if STUB_MODE:
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
        if missing:
            names = ", ".join(p["name"] for p in missing)
            message = f"Não identificado por imagem: {names}"
        return {
            "success": True,
            "detected_products": [],
            "missing_products": missing,
            "message": message,
        }

    try:
        return {
            "success": True,
            "detected_products": [],
            "missing_products": missing,
            "message": message,
        }
    except Exception as exc:
        return _error(str(exc))


def _error(msg: str) -> dict:
    return {
        "success": False,
        "detected_products": [],
        "missing_products": [],
        "message": msg,
    }
