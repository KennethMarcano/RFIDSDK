package com.peripheral.workflow;

import com.peripheral.camera.CameraMicroserviceClient;
import com.peripheral.camera.CameraServiceException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

public class PhotoCaptureService {

    private static final String[] PLACEHOLDER_RESOURCES = {
            "/resources/images.png",
            "resources/images.png",
            "/images.png",
            "images.png"
    };

    private static final String[] FILESYSTEM_PATHS = {
            "src/resources/images.png",
            "resources/images.png"
    };

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
        Path outputFile = outputDir.resolve(String.format("photo_%03d.png", Math.max(1, photoIndex)));

        if (cameraClient != null && cameraClient.isAvailable()) {
            try {
                String savedPath = cameraClient.capture(outputFile.toAbsolutePath().toString());
                context.setPhotoPath(savedPath);
                return;
            } catch (CameraServiceException e) {
                if (mandatory) {
                    throw new IOException("Falha ao capturar foto: " + e.getMessage(), e);
                }
            }
        } else if (mandatory) {
            throw new IOException("Serviço de câmera indisponível para captura obrigatória.");
        }

        Path source = resolvePlaceholderSource();
        if (source != null) {
            Files.copy(source, outputFile, StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.write(outputFile, new byte[0]);
        }
        context.setPhotoPath(outputFile.toAbsolutePath().toString());
    }

    private Path resolvePlaceholderSource() throws IOException {
        ClassLoader loader = PhotoCaptureService.class.getClassLoader();
        for (String resourcePath : PLACEHOLDER_RESOURCES) {
            try (InputStream in = loader.getResourceAsStream(resourcePath)) {
                if (in != null) {
                    Path temp = Files.createTempFile("rfidsdk-placeholder-", ".png");
                    Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
                    temp.toFile().deleteOnExit();
                    return temp;
                }
            }
        }
        for (String filePath : FILESYSTEM_PATHS) {
            Path path = Paths.get(filePath);
            if (Files.isRegularFile(path)) {
                return path.toAbsolutePath();
            }
        }
        return null;
    }
}
