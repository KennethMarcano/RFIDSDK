import os
import shutil

HOST = os.environ.get("CAMERA_SERVICE_HOST", "127.0.0.1")
PORT = int(os.environ.get("CAMERA_SERVICE_PORT", "8765"))

_stub_env = os.environ.get("CAMERA_STUB_MODE")
if _stub_env is None:
    # Sem propriedade explícita: stub só se rpicam não existir (ex.: Windows/dev)
    STUB_MODE = shutil.which("rpicam-hello") is None and shutil.which("libcamera-hello") is None
else:
    STUB_MODE = _stub_env == "1"
