package com.peripheral.camera;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class CameraMicroserviceLifecycle {

    private static final CameraMicroserviceLifecycle INSTANCE = new CameraMicroserviceLifecycle();

    private final CameraMicroserviceConfig config;
    private final CameraMicroserviceClient client;
    private final AtomicReference<Process> processRef = new AtomicReference<>();
    private volatile String lastStartupError;

    private CameraMicroserviceLifecycle() {
        this.config = CameraMicroserviceConfig.fromSystemProperties();
        this.client = new CameraMicroserviceClient(config);
        Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "camera-service-shutdown"));
    }

    public static CameraMicroserviceLifecycle getInstance() {
        return INSTANCE;
    }

    public CameraMicroserviceClient getClient() {
        return client;
    }

    public CameraMicroserviceConfig getConfig() {
        return config;
    }

    public String getLastStartupError() {
        return lastStartupError;
    }

    public synchronized boolean start() {
        if (client.checkHealth()) {
            return true;
        }
        File mainScript = config.getMainScript();
        if (!mainScript.isFile()) {
            lastStartupError = "Script não encontrado: " + mainScript.getAbsolutePath();
            client.setAvailable(false);
            return false;
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    config.getPythonCommand(),
                    mainScript.getAbsolutePath());
            pb.directory(config.getServiceDirectory().toFile());
            pb.redirectErrorStream(true);
            pb.environment().put("CAMERA_STUB_MODE", config.isStubMode() ? "1" : "0");
            pb.environment().put("CAMERA_SERVICE_HOST", config.getHost());
            pb.environment().put("CAMERA_SERVICE_PORT", String.valueOf(config.getPort()));
            // Modelo IMX500 (ONNX + packerOut -> RPK) fica em camera-service/modelCamera
            java.nio.file.Path modelDir = config.getServiceDirectory().resolve("modelCamera");
            pb.environment().put("CAMERA_MODEL_DIR", modelDir.toAbsolutePath().toString());
            // UTF-8 estável no Pi
            pb.environment().put("PYTHONUTF8", "1");
            pb.environment().put("PYTHONUNBUFFERED", "1");

            Process process = pb.start();
            processRef.set(process);

            long deadline = System.currentTimeMillis() + CameraMicroserviceConfig.STARTUP_WAIT_MS;
            boolean httpUp = false;
            while (System.currentTimeMillis() < deadline) {
                // Preferimos /ready (modelo em memória). Aceita /health se o modelo
                // ainda estiver carregando, mas só retorna sucesso com modelo pronto
                // ou com HTTP ok após boa parte do timeout (degradação controlada).
                if (client.checkReady()) {
                    lastStartupError = null;
                    return true;
                }
                if (!httpUp && client.checkHealth()) {
                    httpUp = true;
                }
                if (!process.isAlive()) {
                    lastStartupError = "Processo Python encerrou durante inicialização "
                            + "(verifique pip install -r camera-service/requirements.txt).";
                    client.setAvailable(false);
                    return false;
                }
                try {
                    TimeUnit.MILLISECONDS.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (httpUp) {
                // Serviço no ar, mas modelo pode ter falhado — ainda utilizável p/ captura.
                lastStartupError = "Serviço de câmera online, porém modelo IA não confirmou ready a tempo.";
                client.setAvailable(true);
                return true;
            }
            lastStartupError = "Timeout aguardando serviço de câmera (" + config.getBaseUrl() + ").";
            client.setAvailable(false);
            return false;
        } catch (IOException e) {
            lastStartupError = e.getMessage();
            client.setAvailable(false);
            return false;
        }
    }

    public synchronized void stop() {
        Process process = processRef.getAndSet(null);
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(3, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
        client.setAvailable(false);
    }

    public boolean isRunning() {
        return client.isAvailable();
    }
}
