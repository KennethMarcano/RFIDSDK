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

            Process process = pb.start();
            processRef.set(process);

            long deadline = System.currentTimeMillis() + CameraMicroserviceConfig.STARTUP_WAIT_MS;
            while (System.currentTimeMillis() < deadline) {
                if (client.checkHealth()) {
                    lastStartupError = null;
                    return true;
                }
                if (!process.isAlive()) {
                    lastStartupError = "Processo Python encerrou durante inicialização.";
                    client.setAvailable(false);
                    return false;
                }
                try {
                    TimeUnit.MILLISECONDS.sleep(400);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
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
