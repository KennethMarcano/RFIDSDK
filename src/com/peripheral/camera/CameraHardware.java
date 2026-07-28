package com.peripheral.camera;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Acesso à câmera Sony IMX500 via ferramentas rpicam no Raspberry Pi.
 * O vídeo ao vivo usa {@link CameraFrameStream} (MJPEG embutido no Swing, sem janela nativa).
 */
public final class CameraHardware {

    private static final long DETECT_CACHE_MS = 5_000;
    private static final String[] CANDIDATE_COMMANDS = {
            "rpicam-hello",
            "libcamera-hello"
    };

    private static volatile Boolean cachedRpicamAvailable;
    private static volatile Boolean cachedCameraPresent;
    private static volatile long cacheTimestamp;
    private static volatile String cachedDescribe;
    /** Contador: preview bloqueado enquanto > 0 (suporta begin/end aninhados). */
    private static final AtomicInteger exclusiveCaptureDepth = new AtomicInteger(0);

    private CameraHardware() {
    }

    /** True enquanto {@link #captureStill} / serviço de foto ocupa a câmera. */
    public static boolean isExclusiveCapture() {
        return exclusiveCaptureDepth.get() > 0;
    }

    /**
     * Para o preview e impede o keep-alive de religá-lo até {@link #endExclusiveCapture()}.
     */
    public static void beginExclusiveCapture() {
        if (exclusiveCaptureDepth.getAndIncrement() == 0) {
            stopPreview();
            // Libera o pipeline CSI antes de still/IA (evita post-process IMX500 busy).
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public static void endExclusiveCapture() {
        exclusiveCaptureDepth.updateAndGet(v -> Math.max(0, v - 1));
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

    public static boolean isPreviewRunning() {
        return CameraFrameStream.getInstance().isRunning();
    }

    /**
     * Inicia o vídeo embutido (MJPEG) para painéis Swing — sem janela nativa.
     */
    public static void startPreview() throws CameraServiceException {
        if (isExclusiveCapture()) {
            throw new CameraServiceException("Câmera ocupada capturando foto — aguarde.");
        }
        CameraFrameStream.getInstance().start();
    }

    /**
     * @deprecated Prefer {@link #startPreview()}; mantido por compatibilidade.
     */
    @Deprecated
    public static void startPreview(int x, int y, int width, int height)
            throws CameraServiceException {
        startPreview();
    }

    public static void stopPreview() {
        CameraFrameStream.getInstance().stop();
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
    public static String captureStill(java.nio.file.Path outputPath)
            throws CameraServiceException {
        if (outputPath == null) {
            throw new CameraServiceException("Caminho de saída da foto não informado.");
        }
        // Caller (PhotoCaptureService) deve ter chamado beginExclusiveCapture().
        // Para o preview FORA de qualquer lock de CameraHardware — evita deadlock com
        // CameraFrameStream.startLock (start() pode chamar isCameraPresent sob startLock).
        stopPreview();

        synchronized (CameraHardware.class) {
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
                throw new CameraServiceException(
                        "Não foi possível criar pasta da foto: " + e.getMessage(), e);
            }

            String suffix = outputPath.getFileName() != null
                    ? outputPath.getFileName().toString().toLowerCase()
                    : "";
            java.nio.file.Path target = (suffix.endsWith(".jpg") || suffix.endsWith(".jpeg")
                    || suffix.endsWith(".png"))
                    ? outputPath
                    : outputPath.resolveSibling(outputPath.getFileName() + ".jpg");

            // Pequena pausa para a câmera liberar o stream MJPEG antes do still.
            try {
                Thread.sleep(350);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

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
