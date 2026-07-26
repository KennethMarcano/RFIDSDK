package com.peripheral.camera;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Mini-view embutido: MJPEG via {@code rpicam-vid --nopreview} (sem janela nativa).
 * Otimizado para Raspberry Pi 7": resolução moderada, drop de frames se a UI atrasar.
 */
public final class CameraFrameStream {

    public interface Listener {
        void onFrame(BufferedImage frame);

        void onStatus(String message, boolean error);
    }

    private static final CameraFrameStream INSTANCE = new CameraFrameStream();
    /** Resolução leve para UI fluida no Pi (não precisa de 1080p no monitor). */
    private static final int FRAME_WIDTH = 320;
    private static final int FRAME_HEIGHT = 240;
    private static final int FRAME_RATE = 12;

    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicReference<Process> processRef = new AtomicReference<>();
    private final AtomicReference<BufferedImage> latestFrame = new AtomicReference<>();
    private final AtomicBoolean dispatchPending = new AtomicBoolean(false);
    private final Object startLock = new Object();
    private volatile Thread readerThread;
    private volatile boolean desiredRunning;

    private CameraFrameStream() {
    }

    public static CameraFrameStream getInstance() {
        return INSTANCE;
    }

    public void addListener(Listener listener) {
        if (listener != null) {
            listeners.addIfAbsent(listener);
        }
    }

    public void removeListener(Listener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    public boolean isRunning() {
        Process process = processRef.get();
        return desiredRunning && process != null && process.isAlive();
    }

    public BufferedImage getLatestFrame() {
        return latestFrame.get();
    }

    public void start() throws CameraServiceException {
        synchronized (startLock) {
            if (CameraHardware.isExclusiveCapture()) {
                throw new CameraServiceException("Câmera ocupada capturando foto — aguarde.");
            }
            desiredRunning = true;
            if (isRunning()) {
                return;
            }
            stopInternal();

            String cmd = resolveVidCommand();
            if (cmd == null) {
                desiredRunning = false;
                throw new CameraServiceException(
                        "rpicam-vid não encontrado. Instale rpicam-apps no Raspberry Pi.");
            }
            CameraHardware.invalidateCache();
            if (!CameraHardware.isCameraPresent()) {
                desiredRunning = false;
                throw new CameraServiceException(
                        "Nenhuma câmera detectada. Verifique a conexão da Sony IMX500 (CSI).");
            }

            try {
                // --timeout 0 = stream contínuo (não é “uma foto”).
                // --nopreview evita janela nativa do rpicam.
                List<String> command = new ArrayList<>(Arrays.asList(
                        cmd,
                        "--timeout", "0",
                        "--nopreview",
                        "--codec", "mjpeg",
                        "--width", String.valueOf(FRAME_WIDTH),
                        "--height", String.valueOf(FRAME_HEIGHT),
                        "--framerate", String.valueOf(FRAME_RATE),
                        "--quality", "70",
                        "-o", "-"
                ));
                ProcessBuilder pb = new ProcessBuilder(command);
                pb.redirectError(ProcessBuilder.Redirect.DISCARD);
                Process process = pb.start();
                processRef.set(process);
                notifyStatus("Vídeo ao vivo", false);

                Thread reader = new Thread(() -> readLoop(process), "camera-mjpeg-reader");
                reader.setDaemon(true);
                readerThread = reader;
                reader.start();

                try {
                    TimeUnit.MILLISECONDS.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (!process.isAlive()) {
                    processRef.compareAndSet(process, null);
                    desiredRunning = false;
                    throw new CameraServiceException(
                            "Stream da câmera encerrou imediatamente. Verifique a IMX500.");
                }
            } catch (CameraServiceException e) {
                desiredRunning = false;
                throw e;
            } catch (Exception e) {
                desiredRunning = false;
                stopInternal();
                throw new CameraServiceException("Falha ao iniciar vídeo embutido: " + e.getMessage(), e);
            }
        }
    }

    public void stop() {
        synchronized (startLock) {
            desiredRunning = false;
            stopInternal();
            latestFrame.set(null);
            notifyStatus("Vídeo parado", false);
        }
    }

    private void stopInternal() {
        Process process = processRef.getAndSet(null);
        Thread reader = readerThread;
        readerThread = null;
        if (reader != null) {
            reader.interrupt();
        }
        if (process != null) {
            process.destroy();
            try {
                if (!process.waitFor(3, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(2, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }

    private void readLoop(Process process) {
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream(32 * 1024);
        boolean inFrame = false;
        byte[] buffer = new byte[8 * 1024];
        try (InputStream in = process.getInputStream()) {
            int prev = -1;
            while (desiredRunning && process.isAlive() && !Thread.currentThread().isInterrupted()) {
                int n = in.read(buffer);
                if (n < 0) {
                    break;
                }
                for (int i = 0; i < n; i++) {
                    int b = buffer[i] & 0xFF;
                    if (!inFrame) {
                        if (prev == 0xFF && b == 0xD8) {
                            jpeg.reset();
                            jpeg.write(0xFF);
                            jpeg.write(0xD8);
                            inFrame = true;
                        }
                    } else {
                        jpeg.write(b);
                        if (prev == 0xFF && b == 0xD9) {
                            inFrame = false;
                            publishFrame(jpeg.toByteArray());
                            jpeg.reset();
                        }
                    }
                    prev = b;
                }
            }
        } catch (Exception ignored) {
            // encerramento normal ao parar o processo
        } finally {
            if (processRef.get() == process) {
                processRef.compareAndSet(process, null);
            }
            if (desiredRunning) {
                notifyStatus("Stream interrompido", true);
            }
        }
    }

    private void publishFrame(byte[] jpegBytes) {
        if (jpegBytes == null || jpegBytes.length < 4 || listeners.isEmpty()) {
            return;
        }
        // Se a UI ainda não pintou o frame anterior, descarta — evita fila e “foto travada”.
        if (dispatchPending.get()) {
            return;
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(jpegBytes));
            if (image == null) {
                return;
            }
            latestFrame.set(image);
            if (!dispatchPending.compareAndSet(false, true)) {
                return;
            }
            // Cópia da lista: listeners podem mudar; entrega o frame mais recente no EDT.
            final List<Listener> snapshot = new ArrayList<>(listeners);
            javax.swing.SwingUtilities.invokeLater(() -> {
                try {
                    BufferedImage frame = latestFrame.get();
                    if (frame == null) {
                        return;
                    }
                    for (Listener listener : snapshot) {
                        try {
                            listener.onFrame(frame);
                        } catch (Exception ignored) {
                        }
                    }
                } finally {
                    dispatchPending.set(false);
                }
            });
        } catch (Exception ignored) {
            dispatchPending.set(false);
        }
    }

    private void notifyStatus(String message, boolean error) {
        for (Listener listener : listeners) {
            try {
                listener.onStatus(message, error);
            } catch (Exception ignored) {
            }
        }
    }

    /** Escala síncrona (evita getScaledInstance, que congela o primeiro frame no Pi). */
    public static BufferedImage scaleToFit(BufferedImage source, int maxW, int maxH) {
        if (source == null) {
            return null;
        }
        int w = Math.max(1, maxW);
        int h = Math.max(1, maxH);
        if (w < 8 || h < 8) {
            return source;
        }
        double scale = Math.min((double) w / source.getWidth(), (double) h / source.getHeight());
        int tw = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int th = Math.max(1, (int) Math.round(source.getHeight() * scale));
        if (tw == source.getWidth() && th == source.getHeight()) {
            return source;
        }
        BufferedImage out = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(source, 0, 0, tw, th, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static String resolveVidCommand() {
        String[] candidates = {"rpicam-vid", "libcamera-vid"};
        for (String candidate : candidates) {
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
}
