"""Microserviço local da câmera Sony IMX500 + modelo IA em memória."""

from __future__ import annotations

import logging
import signal
import sys
from contextlib import asynccontextmanager

from fastapi import FastAPI
from pydantic import BaseModel, Field

import analysis_service
import config
import imx500_camera
import model_manager

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
logger = logging.getLogger("camera-service")


@asynccontextmanager
async def lifespan(_app: FastAPI):
    logger.info("Boot camera-service — carregando modelo em %s", config.MODEL_DIR)
    imx500_camera.probe()
    state = model_manager.load()
    if state.loaded:
        logger.info(
            "Modelo pronto (backend=%s, rpk=%s, %d ms)",
            state.backend,
            "ok" if state.rpk_ready else "não",
            state.load_ms,
        )
    else:
        logger.error("Modelo NÃO carregado: %s", state.last_error)
    try:
        yield
    finally:
        logger.info("Shutdown — liberando modelo")
        model_manager.unload()


app = FastAPI(
    title="RFIDSDK Camera Service",
    version="1.1.0",
    lifespan=lifespan,
)


class CaptureRequest(BaseModel):
    output_path: str = Field(..., min_length=1)


class ExpectedProduct(BaseModel):
    code: str
    name: str = ""
    quantity: int = 1
    order_number: str = ""
    volume_index: int = 0


class AnalyzeRequest(BaseModel):
    image_path: str = Field(..., min_length=1)
    expected_products: list[ExpectedProduct] = Field(default_factory=list)


@app.get("/health")
def health():
    cam = imx500_camera.status()
    model = model_manager.status()
    return {
        "ok": True,
        "camera_ready": bool(cam.get("ready")),
        "model_loaded": bool(model.get("model_loaded")),
        "rpk_ready": bool(model.get("rpk_ready")),
        "stub_mode": bool(cam.get("stub_mode")),
        "model_backend": model.get("backend"),
    }


@app.get("/ready")
def ready():
    """Pronto para fallback IA: HTTP up + modelo em memória."""
    model = model_manager.status()
    loaded = bool(model.get("model_loaded"))
    return {
        "ready": loaded,
        "model_loaded": loaded,
        "rpk_ready": bool(model.get("rpk_ready")),
        "backend": model.get("backend"),
        "last_error": model.get("last_error"),
    }


@app.get("/camera/status")
def camera_status():
    imx500_camera.probe()
    return imx500_camera.status()


@app.get("/model/status")
def model_status():
    return model_manager.status()


@app.post("/model/reload")
def model_reload():
    state = model_manager.load()
    return {
        "success": state.loaded,
        "status": model_manager.status(),
        "message": "Modelo recarregado." if state.loaded else (state.last_error or "Falha"),
    }


@app.post("/camera/recalibrate")
def camera_recalibrate():
    success, message = imx500_camera.recalibrate()
    return {"success": success, "message": message}


@app.post("/camera/capture")
def camera_capture(body: CaptureRequest):
    success, path, message = imx500_camera.capture(body.output_path)
    return {"success": success, "path": path, "message": message}


@app.post("/analysis/analyze")
def analysis_analyze(body: AnalyzeRequest):
    products = [p.model_dump() for p in body.expected_products]
    return analysis_service.analyze(body.image_path, products)


def _shutdown(*_args):
    sys.exit(0)


if __name__ == "__main__":
    signal.signal(signal.SIGTERM, _shutdown)
    signal.signal(signal.SIGINT, _shutdown)
    import uvicorn

    uvicorn.run(app, host=config.HOST, port=config.PORT, log_level="info")
