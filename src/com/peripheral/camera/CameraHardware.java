package com.peripheral.camera;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Acesso à câmera Sony IMX500 via ferramentas rpicam no Raspberry Pi.
 * O preview nativo ({@code rpicam-hello --timeout 0}) abre a própria janela de vídeo.
 */
public final class CameraHardware {

    private static final long DETECT_CACHE_MS = 5_000;
    private static final AtomicReference<Process> previewProcess = new AtomicReference<>();
    private static final String[] CANDIDATE_COMMANDS = {
            "rpicam-hello",
            "libcamera-hello"
    };

    private static volatile Boolean cachedRpicamAvailable;
    private static volatile Boolean cachedCameraPresent;
    private static volatile long cacheTimestamp;
    private static volatile String cachedDescribe;

    private CameraHardware() {
    }

    public static boolean isRpicamAvailable() {
        refreshCacheIfNeeded(false);
        Boolean cached = cachedRpicamAvailable;
        return cached != null && cached;
    }

    public static boolean isCameraPresent() {
        refreshCacheIfNeeded(false);
        Boolean cached = cachedCameraPresent;
        return cached != null && cached;
    }

    public static String describeCamera() {
        refreshCacheIfNeeded(true);
        return cachedDescribe != null ? cachedDescribe : "Sem informação da câmera.";
    }

    public static void invalidateCache() {
        cacheTimestamp = 0;
        cachedDescribe = null;
    }

    private static synchronized void refreshCacheIfNeeded(boolean forceDescribe) {
        long now = System.currentTimeMillis();
        boolean fresh = (now - cacheTimestamp) < DETECT_CACHE_MS
                && cachedRpicamAvailable != null
                && cachedCameraPresent != null;
        if (fresh && (!forceDescribe || cachedDescribe != null)) {
            return;
        }
        String cmd = resolveHelloCommandUncached();
        cachedRpicamAvailable = cmd != null;
        if (cmd == null) {
            cachedCameraPresent = false;
            cachedDescribe = "Ferramenta rpicam não encontrada no PATH.";
            cacheTimestamp = now;
            return;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd, "--list-cameras");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output = readProcessOutput(process, 8_000);
            process.waitFor();
            String trimmed = output.trim();
            String lower = trimmed.toLowerCase();
            boolean present = !lower.contains("no cameras available")
                    && !lower.contains("no camera available")
                    && (lower.contains("available cameras")
                    || lower.contains("imx500")
                    || lower.contains("/base/")
                    || lower.matches("(?s).*\\b0\\s*:\\s*.*"));
            cachedCameraPresent = present;
            if (trimmed.isEmpty()) {
                cachedDescribe = present
                        ? "Câmera detectada."
                        : "Nenhuma informação retornada por " + cmd + " --list-cameras";
            } else if (lower.contains("imx500")) {
                cachedDescribe = "Sony IMX500 detectada.\n" + trimmed;
            } else {
                cachedDescribe = trimmed;
            }
        } catch (Exception e) {
            cachedCameraPresent = false;
            cachedDescribe = "Falha ao listar câmeras: " + e.getMessage();
        }
        cacheTimestamp = System.currentTimeMillis();
    }

    public static synchronized boolean isPreviewRunning() {
        Process process = previewProcess.get();
        return process != null && process.isAlive();
    }

    /**
     * Abre a janela nativa de preview com vídeo contínuo (sem timeout).
     */
    public static synchronized void startPreview() throws CameraServiceException {
        if (isPreviewRunning()) {
            return;
        }
        invalidateCache();
        String cmd = resolveHelloCommandUncached();
        if (cmd == null) {
            throw new CameraServiceException(
                    "rpicam-hello não encontrado. Instale rpicam-apps no Raspberry Pi.");
        }
        if (!isCameraPresent()) {
            throw new CameraServiceException(
                    "Nenhuma câmera detectada. Verifique a conexão da Sony IMX500 (CSI).");
        }
        try {
            List<String> command = new ArrayList<>(Arrays.asList(
                    cmd,
                    "--timeout", "0",
                    "--preview", "0,0,960,720"
            ));
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            previewProcess.set(process);
            Thread drain = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    while (reader.readLine() != null) {
                        // descarta saída para evitar bloqueio do pipe
                    }
                } catch (Exception ignored) {
                }
            }, "rpicam-preview-drain");
            drain.setDaemon(true);
            drain.start();

            try {
                TimeUnit.MILLISECONDS.sleep(600);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (!process.isAlive()) {
                previewProcess.compareAndSet(process, null);
                throw new CameraServiceException(
                        "Preview encerrou imediatamente. Verifique a câmera e tente "
                                + cmd + " --timeout 0 no terminal.");
            }
        } catch (CameraServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new CameraServiceException("Falha ao abrir preview: " + e.getMessage(), e);
        }
    }

    public static synchronized void stopPreview() {
        Process process = previewProcess.getAndSet(null);
        if (process == null) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    /**
     * Recalibração/verificação via hardware (lista câmeras novamente).
     */
    public static String recalibrate() throws CameraServiceException {
        invalidateCache();
        if (!isRpicamAvailable()) {
            throw new CameraServiceException(
                    "rpicam não encontrado. Instale rpicam-apps no Raspberry Pi.");
        }
        if (!isCameraPresent()) {
            throw new CameraServiceException(
                    "Nenhuma câmera detectada após verificação. Confira o cabo CSI da IMX500.");
        }
        return "Câmera verificada / pronta (IMX500).\n" + describeCamera();
    }

    /**
     * Captura still com rpicam-still. Encerra preview se estiver aberto (câmera exclusiva).
     *
     * @return caminho absoluto do arquivo gerado
     */
    public static synchronized String captureStill(java.nio.file.Path outputPath)
            throws CameraServiceException {
        if (outputPath == null) {
            throw new CameraServiceException("Caminho de saída da foto não informado.");
        }
        stopPreview();
        String still = resolveStillCommand();
        if (still == null) {
            throw new CameraServiceException(
                    "rpicam-still não encontrado. Instale rpicam-apps no Raspberry Pi.");
        }
        try {
            java.nio.file.Files.createDirectories(outputPath.getParent() != null
                    ? outputPath.getParent()
                    : java.nio.file.Paths.get("."));
        } catch (Exception e) {
            throw new CameraServiceException("Não foi possível criar pasta da foto: " + e.getMessage(), e);
        }

        String suffix = outputPath.getFileName() != null
                ? outputPath.getFileName().toString().toLowerCase()
                : "";
        java.nio.file.Path target = (suffix.endsWith(".jpg") || suffix.endsWith(".jpeg")
                || suffix.endsWith(".png"))
                ? outputPath
                : outputPath.resolveSibling(outputPath.getFileName() + ".jpg");

        List<String> command = new ArrayList<>(Arrays.asList(
                still,
                "--nopreview",
                "--immediate",
                "--timeout", "2000",
                "-o", target.toAbsolutePath().toString()
        ));
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output = readProcessOutput(process, 30_000);
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new CameraServiceException("Timeout na captura rpicam-still.");
            }
            if (process.exitValue() != 0
                    || !java.nio.file.Files.isRegularFile(target)
                    || java.nio.file.Files.size(target) == 0) {
                String detail = output != null ? output.trim() : "";
                throw new CameraServiceException(
                        "Falha na captura rpicam-still"
                                + (detail.isEmpty() ? "." : ": " + detail));
            }
            return target.toAbsolutePath().toString();
        } catch (CameraServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new CameraServiceException("Erro ao capturar foto: " + e.getMessage(), e);
        }
    }

    static String resolveHelloCommand() {
        return resolveHelloCommandUncached();
    }

    static String resolveStillCommand() {
        String[] stills = {"rpicam-still", "libcamera-still"};
        for (String candidate : stills) {
            if (commandExists(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static String resolveHelloCommandUncached() {
        for (String candidate : CANDIDATE_COMMANDS) {
            if (commandExists(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean commandExists(String command) {
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            ProcessBuilder pb = os.contains("win")
                    ? new ProcessBuilder("where", command)
                    : new ProcessBuilder("sh", "-c", "command -v " + command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean finished = process.waitFor(3, TimeUnit.SECONDS);
            return finished && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String readProcessOutput(Process process, long timeoutMs) throws Exception {
        StringBuilder sb = new StringBuilder();
        long deadline = System.currentTimeMillis() + timeoutMs;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            while (System.currentTimeMillis() < deadline) {
                if (reader.ready()) {
                    String line = reader.readLine();
                    if (line == null) {
                        break;
                    }
                    sb.append(line).append('\n');
                    continue;
                }
                if (!process.isAlive()) {
                    while (reader.ready()) {
                        String line = reader.readLine();
                        if (line == null) {
                            break;
                        }
                        sb.append(line).append('\n');
                    }
                    break;
                }
                Thread.sleep(40);
            }
        }
        return sb.toString();
    }
}
