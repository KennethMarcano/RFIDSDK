"""Microserviço local da câmera Sony IMX500 + análise por imagem."""

from __future__ import annotations

import signal
import sys

from fastapi import FastAPI
from pydantic import BaseModel, Field

import analysis_service
import config
import imx500_camera

app = FastAPI(title="RFIDSDK Camera Service", version="1.0.0")


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
    return {"ok": True}


@app.get("/camera/status")
def camera_status():
    return imx500_camera.status()


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
