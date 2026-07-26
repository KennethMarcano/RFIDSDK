package com.peripheral.workflow;

import com.peripheral.camera.CameraHardware;
import com.peripheral.camera.CameraMicroserviceClient;
import com.peripheral.camera.CameraMicroserviceLifecycle;
import com.peripheral.camera.CameraServiceException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Captura foto real da Sony IMX500 (serviço HTTP ou rpicam-still).
 * Não usa mais o logo/placeholder da aplicação como “foto”.
 */
public class PhotoCaptureService {

    private final CameraMicroserviceClient cameraClient;

    public PhotoCaptureService() {
        this(null);
    }

    public PhotoCaptureService(CameraMicroserviceClient cameraClient) {
        this.cameraClient = cameraClient;
    }

    public void capturePhoto(WorkflowContext context, Path sessionDirectory, int photoIndex)
            throws IOException {
        capturePhoto(context, sessionDirectory, photoIndex, false);
    }

    public void capturePhoto(WorkflowContext context, Path sessionDirectory, int photoIndex,
                             boolean mandatory) throws IOException {
        Path outputDir = sessionDirectory;
        if (outputDir == null) {
            outputDir = Paths.get(System.getProperty("java.io.tmpdir"), "rfidsdk-workflow");
        }
        Files.createDirectories(outputDir);
        // JPG: rpicam-still grava melhor neste formato; o preview Swing abre JPG normalmente
        Path outputFile = outputDir.resolve(String.format("photo_%03d.jpg", Math.max(1, photoIndex)));

        // Preview MJPEG e keep-alive disputam a câmera — bloquear até terminar a still.
        CameraHardware.beginExclusiveCapture();
        IOException lastError = null;
        try {
            CameraMicroserviceClient client = cameraClient;
            if (client == null) {
                client = CameraMicroserviceLifecycle.getInstance().getClient();
            }
            if (client != null) {
                if (!client.isAvailable()) {
                    client.checkHealth();
                }
                if (!client.isAvailable()) {
                    CameraMicroserviceLifecycle.getInstance().start();
                    client.checkHealth();
                }
                if (client.isAvailable()) {
                    try {
                        String savedPath = client.capture(outputFile.toAbsolutePath().toString());
                        if (savedPath != null && !savedPath.trim().isEmpty()) {
                            Path saved = Paths.get(savedPath);
                            // Stub 1x1 ou arquivo vazio: não aceitar — tentar rpicam real
                            if (Files.isRegularFile(saved) && Files.size(saved) >= 2048) {
                                context.setPhotoPath(savedPath);
                                return;
                            }
                            lastError = new IOException(
                                    "Serviço devolveu imagem inválida/stub (" + savedPath + ").");
                        } else {
                            lastError = new IOException("Serviço de câmera retornou caminho vazio.");
                        }
                    } catch (CameraServiceException e) {
                        lastError = new IOException("Falha no serviço de câmera: " + e.getMessage(), e);
                    }
                } else {
                    lastError = new IOException("Serviço de câmera HTTP indisponível.");
                }
            }

            try {
                String hardwarePath = CameraHardware.captureStill(outputFile);
                context.setPhotoPath(hardwarePath);
                return;
            } catch (CameraServiceException e) {
                lastError = new IOException("Falha rpicam-still: " + e.getMessage(), e);
            }

            String message = lastError != null ? lastError.getMessage()
                    : "Não foi possível capturar foto com a câmera IMX500.";
            if (mandatory) {
                throw lastError != null ? lastError : new IOException(message);
            }
            throw new IOException(message);
        } finally {
            CameraHardware.endExclusiveCapture();
        }
    }
}
