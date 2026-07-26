package com.peripheral.app;

import com.peripheral.camera.CameraFrameStream;
import com.peripheral.camera.CameraHardware;
import com.peripheral.camera.CameraMicroserviceClient;
import com.peripheral.camera.CameraMicroserviceLifecycle;
import com.peripheral.camera.CameraServiceException;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Teste da câmera com vídeo ao vivo + inferência do modelo IMX500 (ONNX)
 * sobre frames capturados periodicamente, com overlay de detecções.
 */
public class CameraTestDialog extends JDialog implements CameraFrameStream.Listener {

    /** Intervalo longo: backend IMX500 RPK precisa parar o vídeo para capturar no sensor. */
    private static final int AI_INTERVAL_MS = 8000;

    private final Consumer<String> logConsumer;
    private final JLabel lbVideo = new JLabel("Abrindo câmera...", SwingConstants.CENTER);
    private final JLabel lbAiStatus = new JLabel("IA: desligada — vídeo ao vivo");
    private final JLabel lbDetections = new JLabel("Detecções: —");
    private final ThemedButton btnToggleAi =
            WorkflowUiTheme.button("IA: desligada", ThemedButton.Variant.SECONDARY);
    private final ThemedButton btnClose =
            WorkflowUiTheme.button("Fechar", ThemedButton.Variant.SECONDARY);

    private final AtomicReference<BufferedImage> latestFrame = new AtomicReference<>();
    private final AtomicReference<List<CameraMicroserviceClient.Detection>> detections =
            new AtomicReference<>(Collections.emptyList());
    /** IA off por padrão: o vídeo fica fluido; ligar IA pausa o stream a cada ciclo (RPK). */
    private final AtomicBoolean aiEnabled = new AtomicBoolean(false);
    private final AtomicBoolean aiBusy = new AtomicBoolean(false);
    private final AtomicBoolean dialogAlive = new AtomicBoolean(false);
    private final AtomicBoolean paintPending = new AtomicBoolean(false);

    private Timer aiTimer;
    private Path tempFramePath;

    public CameraTestDialog(Window owner, Consumer<String> logConsumer) {
        super(owner, "Câmera + IA", ModalityType.MODELESS);
        this.logConsumer = logConsumer;

        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.setOpaque(true);
        content.setBackground(WorkflowUiTheme.BG_PAGE);
        content.setBorder(WorkflowUiTheme.empty(12, 12, 12, 12));

        lbVideo.setOpaque(true);
        lbVideo.setBackground(WorkflowUiTheme.MONITOR_BG);
        lbVideo.setForeground(WorkflowUiTheme.MONITOR_CAPTION);
        lbVideo.setFont(lbVideo.getFont().deriveFont(Font.PLAIN, 13f));
        lbVideo.setBorder(BorderFactory.createLineBorder(WorkflowUiTheme.MONITOR_BORDER, 1));
        lbVideo.setPreferredSize(new Dimension(640, 400));
        lbVideo.setHorizontalAlignment(SwingConstants.CENTER);
        lbVideo.setVerticalAlignment(SwingConstants.CENTER);

        lbAiStatus.setFont(WorkflowUiTheme.fontMeta(lbAiStatus));
        lbAiStatus.setForeground(WorkflowUiTheme.TEXT_SECONDARY);
        lbDetections.setFont(WorkflowUiTheme.fontStatus(lbDetections));
        lbDetections.setForeground(WorkflowUiTheme.TEXT_PRIMARY);

        JPanel info = new JPanel();
        info.setOpaque(false);
        info.setLayout(new BoxLayout(info, BoxLayout.Y_AXIS));
        lbAiStatus.setAlignmentX(Component.LEFT_ALIGNMENT);
        lbDetections.setAlignmentX(Component.LEFT_ALIGNMENT);
        info.add(lbAiStatus);
        info.add(Box.createVerticalStrut(4));
        info.add(lbDetections);

        JPanel south = new JPanel(new BorderLayout(8, 0));
        south.setOpaque(false);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        btnToggleAi.addActionListener(e -> toggleAi());
        btnClose.addActionListener(e -> closeDialog());
        buttons.add(btnToggleAi);
        buttons.add(btnClose);
        south.add(info, BorderLayout.CENTER);
        south.add(buttons, BorderLayout.EAST);

        content.add(lbVideo, BorderLayout.CENTER);
        content.add(south, BorderLayout.SOUTH);

        setContentPane(content);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                dialogAlive.set(true);
                startVideoNow();
                startAiLoop();
            }

            @Override
            public void windowClosed(WindowEvent e) {
                dialogAlive.set(false);
                stopAiLoop();
                stopVideo();
                deleteTempFrame();
            }
        });
        pack();
        WorkflowUiTheme.clampToScreen(this, owner);
    }

    public void showDialog() {
        dialogAlive.set(true);
        setVisible(true);
        toFront();
        startVideoNow();
        startAiLoop();
    }

    private void toggleAi() {
        boolean next = !aiEnabled.get();
        aiEnabled.set(next);
        btnToggleAi.setText(next ? "IA: ligada" : "IA: desligada");
        if (!next) {
            detections.set(Collections.emptyList());
            lbDetections.setText("Detecções: (IA desligada)");
            lbAiStatus.setText("IA: pausada");
            WorkflowUiTheme.setStatusColor(lbAiStatus, WorkflowUiTheme.TEXT_MUTED);
        } else {
            lbAiStatus.setText("IA: analisando...");
            WorkflowUiTheme.setStatusColor(lbAiStatus, WorkflowUiTheme.WARNING);
            scheduleAiNow();
        }
    }

    private void startVideoNow() {
        lbVideo.setIcon(null);
        lbVideo.setText("Abrindo câmera...");
        CameraFrameStream.getInstance().addListener(this);
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                try {
                    CameraHardware.startPreview();
                    return null;
                } catch (CameraServiceException e) {
                    return e.getMessage();
                }
            }

            @Override
            protected void done() {
                try {
                    String error = get();
                    if (error != null) {
                        lbVideo.setText(error);
                        log("ERRO câmera: " + error);
                        JOptionPane.showMessageDialog(CameraTestDialog.this, error,
                                "Câmera", JOptionPane.ERROR_MESSAGE);
                    } else {
                        log("Vídeo da câmera iniciado (teste + IA)");
                    }
                } catch (Exception e) {
                    lbVideo.setText(e.getMessage());
                }
            }
        }.execute();
    }

    private void startAiLoop() {
        if (aiTimer != null && aiTimer.isRunning()) {
            return;
        }
        refreshModelStatus();
        aiTimer = new Timer(AI_INTERVAL_MS, e -> runAiOnLatestFrame());
        aiTimer.setInitialDelay(800);
        aiTimer.start();
    }

    private void scheduleAiNow() {
        if (aiTimer != null) {
            aiTimer.restart();
        }
        runAiOnLatestFrame();
    }

    private void stopAiLoop() {
        if (aiTimer != null) {
            aiTimer.stop();
            aiTimer = null;
        }
    }

    private void refreshModelStatus() {
        new SwingWorker<Boolean, Void>() {
            @Override
            protected Boolean doInBackground() {
                CameraMicroserviceLifecycle life = CameraMicroserviceLifecycle.getInstance();
                if (!life.getClient().isAvailable()) {
                    life.start();
                }
                return life.getClient().checkReady();
            }

            @Override
            protected void done() {
                try {
                    boolean ready = Boolean.TRUE.equals(get());
                    if (ready) {
                        lbAiStatus.setText("IA: modelo carregado — detectando no vídeo");
                        WorkflowUiTheme.setStatusColor(lbAiStatus, WorkflowUiTheme.SUCCESS);
                    } else {
                        lbAiStatus.setText("IA: modelo não pronto (serviço / venv / deps)");
                        WorkflowUiTheme.setStatusColor(lbAiStatus, WorkflowUiTheme.WARNING);
                    }
                } catch (Exception e) {
                    lbAiStatus.setText("IA: erro ao verificar modelo");
                    WorkflowUiTheme.setStatusColor(lbAiStatus, WorkflowUiTheme.DANGER);
                }
            }
        }.execute();
    }

    private void runAiOnLatestFrame() {
        if (!dialogAlive.get() || !aiEnabled.get() || !aiBusy.compareAndSet(false, true)) {
            return;
        }
        BufferedImage frame = latestFrame.get();
        if (frame == null) {
            aiBusy.set(false);
            return;
        }
        final BufferedImage snapshot = copyImage(frame);
        SwingUtilities.invokeLater(() -> {
            lbAiStatus.setText("IA: capturando no sensor (vídeo pausado)...");
            WorkflowUiTheme.setStatusColor(lbAiStatus, WorkflowUiTheme.WARNING);
            lbVideo.setText("Analisando com IA — aguarde");
        });
        new SwingWorker<CameraMicroserviceClient.AnalysisResult, Void>() {
            private String error;

            @Override
            protected CameraMicroserviceClient.AnalysisResult doInBackground() {
                // Backend imx500_rpk ignora o JPG e captura live no sensor — precisa exclusividade.
                CameraHardware.beginExclusiveCapture();
                try {
                    Thread.sleep(300);
                    Path path = ensureTempFramePath();
                    if (snapshot != null) {
                        ImageIO.write(snapshot, "jpg", path.toFile());
                    }
                    CameraMicroserviceClient client =
                            CameraMicroserviceLifecycle.getInstance().getClient();
                    if (!client.checkReady() && !client.checkHealth()) {
                        error = "Serviço de câmera/IA indisponível";
                        return null;
                    }
                    return client.analyze(path.toAbsolutePath().toString(),
                            Collections.emptyList());
                } catch (Exception e) {
                    error = e.getMessage();
                    return null;
                } finally {
                    CameraHardware.endExclusiveCapture();
                    try {
                        if (dialogAlive.get()) {
                            CameraHardware.startPreview();
                        }
                    } catch (Exception ignored) {
                    }
                }
            }

            @Override
            protected void done() {
                aiBusy.set(false);
                if (!dialogAlive.get()) {
                    return;
                }
                try {
                    CameraMicroserviceClient.AnalysisResult result = get();
                    if (result == null) {
                        lbAiStatus.setText("IA: " + (error != null ? error : "sem resultado"));
                        WorkflowUiTheme.setStatusColor(lbAiStatus, WorkflowUiTheme.WARNING);
                        return;
                    }
                    if (!result.isSuccess()) {
                        lbAiStatus.setText("IA: " + result.getMessage());
                        WorkflowUiTheme.setStatusColor(lbAiStatus, WorkflowUiTheme.WARNING);
                        return;
                    }
                    List<CameraMicroserviceClient.Detection> dets =
                            uniqueByCodeMaxConfidence(result.getDetections());
                    // Sempre substitui a lista: produto removido some no próximo ciclo.
                    detections.set(dets);
                    updateDetectionsLabel(dets);
                    lbAiStatus.setText("IA: ok — "
                            + dets.size()
                            + " código(s) · próximo ciclo em "
                            + (AI_INTERVAL_MS / 1000) + "s");
                    WorkflowUiTheme.setStatusColor(lbAiStatus, WorkflowUiTheme.SUCCESS);
                } catch (Exception e) {
                    lbAiStatus.setText("IA: " + e.getMessage());
                    WorkflowUiTheme.setStatusColor(lbAiStatus, WorkflowUiTheme.DANGER);
                }
            }
        }.execute();
    }

    private void updateDetectionsLabel(List<CameraMicroserviceClient.Detection> dets) {
        List<CameraMicroserviceClient.Detection> unique = uniqueByCodeMaxConfidence(dets);
        if (unique.isEmpty()) {
            lbDetections.setText("Código: — (nenhum produto na cena)");
            return;
        }
        // Um código por linha — só o código e o % (sem repetir).
        StringBuilder sb = new StringBuilder("<html>");
        for (int i = 0; i < unique.size(); i++) {
            if (i > 0) {
                sb.append("<br>");
            }
            CameraMicroserviceClient.Detection d = unique.get(i);
            sb.append("Código: <b>")
                    .append(escapeHtml(d.getCode()))
                    .append("</b> — ")
                    .append(String.format(java.util.Locale.US, "%.0f%%",
                            confidencePercent(d.getConfidence())));
            if (i >= 4) {
                sb.append("<br>…");
                break;
            }
        }
        sb.append("</html>");
        lbDetections.setText(sb.toString());
    }

    /** Um código por entrada: mantém só a maior confiança neste ciclo. */
    private static List<CameraMicroserviceClient.Detection> uniqueByCodeMaxConfidence(
            List<CameraMicroserviceClient.Detection> dets) {
        if (dets == null || dets.isEmpty()) {
            return Collections.emptyList();
        }
        java.util.LinkedHashMap<String, CameraMicroserviceClient.Detection> byCode =
                new java.util.LinkedHashMap<>();
        for (CameraMicroserviceClient.Detection d : dets) {
            if (d == null) {
                continue;
            }
            String key = normalizeCode(d.getCode());
            if (key.isEmpty()) {
                continue;
            }
            CameraMicroserviceClient.Detection prev = byCode.get(key);
            if (prev == null || d.getConfidence() > prev.getConfidence()) {
                byCode.put(key, d);
            }
        }
        return new ArrayList<>(byCode.values());
    }

    private static String normalizeCode(String code) {
        if (code == null) {
            return "";
        }
        String s = code.trim();
        // Remove sufixo de categoria do log rpicam: "003509[0]" → "003509"
        int bracket = s.indexOf('[');
        if (bracket > 0) {
            s = s.substring(0, bracket).trim();
        }
        return s.toUpperCase(java.util.Locale.ROOT);
    }

    /** Aceita confiança 0–1 ou já em percentual 0–100. */
    private static double confidencePercent(double confidence) {
        if (confidence < 0) {
            return 0;
        }
        if (confidence <= 1.0) {
            return confidence * 100.0;
        }
        return Math.min(100.0, confidence);
    }

    private static String escapeHtml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private Path ensureTempFramePath() throws IOException {
        if (tempFramePath == null) {
            tempFramePath = Files.createTempFile("rfidsdk-cam-ai-", ".jpg");
            tempFramePath.toFile().deleteOnExit();
        }
        return tempFramePath;
    }

    private void deleteTempFrame() {
        if (tempFramePath != null) {
            try {
                Files.deleteIfExists(tempFramePath);
            } catch (IOException ignored) {
            }
            tempFramePath = null;
        }
    }

    private void stopVideo() {
        CameraFrameStream.getInstance().removeListener(this);
        CameraHardware.stopPreview();
        lbVideo.setIcon(null);
        latestFrame.set(null);
    }

    private void closeDialog() {
        dialogAlive.set(false);
        stopAiLoop();
        stopVideo();
        deleteTempFrame();
        dispose();
    }

    @Override
    public void onFrame(BufferedImage frame) {
        if (frame == null || aiBusy.get()) {
            return;
        }
        latestFrame.set(frame);
        if (!paintPending.compareAndSet(false, true)) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            try {
                if (!isDisplayable() || aiBusy.get()) {
                    return;
                }
                BufferedImage current = latestFrame.get();
                if (current == null) {
                    return;
                }
                List<CameraMicroserviceClient.Detection> dets = detections.get();
                BufferedImage painted = dets == null || dets.isEmpty()
                        ? current
                        : drawDetections(current, dets);
                BufferedImage scaled = CameraFrameStream.scaleToFit(
                        painted, lbVideo.getWidth(), lbVideo.getHeight());
                if (scaled == null) {
                    return;
                }
                lbVideo.setText(null);
                lbVideo.setIcon(new ImageIcon(scaled));
            } finally {
                paintPending.set(false);
            }
        });
    }

    private static BufferedImage drawDetections(BufferedImage source,
                                                List<CameraMicroserviceClient.Detection> dets) {
        BufferedImage out = copyImage(source);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = out.getWidth();
            int h = out.getHeight();
            List<CameraMicroserviceClient.Detection> unique = uniqueByCodeMaxConfidence(dets);
            for (CameraMicroserviceClient.Detection d : unique) {
                double[] box = d.getBox();
                int x1 = toPx(box[0], w);
                int y1 = toPx(box[1], h);
                int x2 = toPx(box[2], w);
                int y2 = toPx(box[3], h);
                if (x2 < x1) {
                    int t = x1;
                    x1 = x2;
                    x2 = t;
                }
                if (y2 < y1) {
                    int t = y1;
                    y1 = y2;
                    y2 = t;
                }
                // Se o box veio inválido / zerado, badge no canto
                if (x2 - x1 < 4 || y2 - y1 < 4) {
                    continue;
                }
                g.setColor(new Color(0xFF, 0xBB, 0x00));
                g.setStroke(new BasicStroke(2.5f));
                g.drawRect(x1, y1, Math.max(1, x2 - x1), Math.max(1, y2 - y1));
                String label = d.getCode() + " "
                        + String.format(java.util.Locale.US, "%.0f%%",
                        confidencePercent(d.getConfidence()));
                g.setFont(g.getFont().deriveFont(Font.BOLD, 14f));
                FontMetrics fm = g.getFontMetrics();
                int tw = fm.stringWidth(label) + 8;
                int th = fm.getHeight();
                int ty = Math.max(th + 2, y1);
                g.setColor(new Color(0x25, 0x2F, 0x3D, 200));
                g.fillRect(x1, ty - th, tw, th);
                g.setColor(Color.WHITE);
                g.drawString(label, x1 + 4, ty - 4);
            }
            // Lista compacta no topo se houver detecções sem bbox útil
            boolean anyBox = false;
            for (CameraMicroserviceClient.Detection d : unique) {
                double[] b = d.getBox();
                if (toPx(b[2], w) - toPx(b[0], w) >= 4) {
                    anyBox = true;
                    break;
                }
            }
            if (!anyBox && !unique.isEmpty()) {
                int y = 18;
                g.setFont(g.getFont().deriveFont(Font.BOLD, 13f));
                for (CameraMicroserviceClient.Detection d : unique) {
                    String label = "● " + d.getCode() + " "
                            + String.format(java.util.Locale.US, "%.0f%%",
                            confidencePercent(d.getConfidence()));
                    g.setColor(new Color(0x25, 0x2F, 0x3D, 200));
                    FontMetrics fm = g.getFontMetrics();
                    g.fillRect(8, y - fm.getAscent(), fm.stringWidth(label) + 10, fm.getHeight());
                    g.setColor(new Color(0xFF, 0xBB, 0x00));
                    g.drawString(label, 12, y);
                    y += fm.getHeight() + 4;
                }
            }
        } finally {
            g.dispose();
        }
        return out;
    }

    private static int toPx(double v, int size) {
        if (Math.abs(v) <= 1.5) {
            return (int) Math.round(v * size);
        }
        return (int) Math.round(v);
    }

    private static BufferedImage copyImage(BufferedImage src) {
        BufferedImage copy = new BufferedImage(src.getWidth(), src.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = copy.createGraphics();
        try {
            g.drawImage(src, 0, 0, null);
        } finally {
            g.dispose();
        }
        return copy;
    }

    @Override
    public void onStatus(String message, boolean error) {
        SwingUtilities.invokeLater(() -> {
            if (lbVideo.getIcon() == null && message != null) {
                lbVideo.setText(message);
            }
        });
    }

    private void log(String message) {
        if (logConsumer != null) {
            logConsumer.accept(message);
        }
    }
}
