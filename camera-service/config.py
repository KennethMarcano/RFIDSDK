"""Configuração do microserviço de câmera / modelo IMX500."""

from __future__ import annotations

import os
import shutil
from pathlib import Path

HOST = os.environ.get("CAMERA_SERVICE_HOST", "127.0.0.1")
PORT = int(os.environ.get("CAMERA_SERVICE_PORT", "8765"))

SERVICE_DIR = Path(__file__).resolve().parent
MODEL_DIR = Path(
    os.environ.get("CAMERA_MODEL_DIR", str(SERVICE_DIR / "modelCamera"))
).resolve()

# Artefatos do modelo (Sony IMX500 Converter / packer)
MODEL_ONNX_NAME = os.environ.get("CAMERA_MODEL_ONNX", "model_imx.onnx")
MODEL_LABELS_NAME = os.environ.get("CAMERA_MODEL_LABELS", "labels.txt")
MODEL_PACKER_ZIP_NAME = os.environ.get("CAMERA_MODEL_PACKER_ZIP", "packerOut.zip")
MODEL_RPK_NAME = os.environ.get("CAMERA_MODEL_RPK", "network.rpk")
MODEL_DNN_PARAMS_NAME = "dnnParams.xml"

DETECTION_THRESHOLD = float(os.environ.get("CAMERA_DETECT_THRESHOLD", "0.45"))
INPUT_SIZE = int(os.environ.get("CAMERA_MODEL_INPUT_SIZE", "640"))

_stub_env = os.environ.get("CAMERA_STUB_MODE")
if _stub_env is None:
    STUB_MODE = shutil.which("rpicam-hello") is None and shutil.which("libcamera-hello") is None
else:
    STUB_MODE = _stub_env == "1"


def model_onnx_path() -> Path:
    return MODEL_DIR / MODEL_ONNX_NAME


def model_labels_path() -> Path:
    return MODEL_DIR / MODEL_LABELS_NAME


def model_packer_zip_path() -> Path:
    return MODEL_DIR / MODEL_PACKER_ZIP_NAME


def model_rpk_path() -> Path:
    return MODEL_DIR / MODEL_RPK_NAME


def model_dnn_params_path() -> Path:
    return MODEL_DIR / MODEL_DNN_PARAMS_NAME
