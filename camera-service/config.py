import os

HOST = os.environ.get("CAMERA_SERVICE_HOST", "127.0.0.1")
PORT = int(os.environ.get("CAMERA_SERVICE_PORT", "8765"))
STUB_MODE = os.environ.get("CAMERA_STUB_MODE", "1") == "1"
