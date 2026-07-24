package com.peripheral.app;

import com.peripheral.camera.CameraFrameStream;
import com.peripheral.camera.CameraHardware;
import com.peripheral.camera.CameraMicroserviceClient;
import com.peripheral.camera.CameraMicroserviceLifecycle;
import com.peripheral.camera.CameraServiceException;

import javax.swing.*;
import java.awt.*;
import java.awt.event.HierarchyEvent;
import java.awt.event.HierarchyListener;
import java.awt.image.BufferedImage;

/**
 * Mini-view de câmera embutido no Swing (frames MJPEG), no estilo do monitor da balança.
 */
public class CameraLiveMonitorPanel extends JPanel implements CameraFrameStream.Listener {

    private final JLabel lbCaption = new JLabel("MONITOR DA CÂMERA");
    private final JLabel lbVideo = new JLabel("Aguardando vídeo...", SwingConstants.CENTER);
    private final ThemedButton btnRecalibrate =
            WorkflowUiTheme.button("Recalibrar", ThemedButton.Variant.SECONDARY)
                    .withSize(ThemedButton.Size.SMALL);

    private final Timer keepAliveTimer;
    private boolean liveDesired;
    private boolean starting;
    private ImageIcon currentIcon;

    public CameraLiveMonitorPanel() {
        super(new BorderLayout(0, 4));
        setOpaque(true);
        setBackground(WorkflowUiTheme.MONITOR_BG);
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(WorkflowUiTheme.MONITOR_BORDER, 1),
                WorkflowUiTheme.empty(8, 10, 8, 10)));

        lbCaption.setFont(lbCaption.getFont().deriveFont(Font.BOLD, 12f));
        lbCaption.setForeground(WorkflowUiTheme.MONITOR_CAPTION);

        btnRecalibrate.addActionListener(e -> recalibrate());

        JPanel header = new JPanel(new BorderLayout(8, 0));
        header.setOpaque(false);
        header.add(lbCaption, BorderLayout.WEST);
        header.add(btnRecalibrate, BorderLayout.EAST);

        lbVideo.setOpaque(true);
        lbVideo.setBackground(WorkflowUiTheme.MONITOR_ROW_BG);
        lbVideo.setForeground(WorkflowUiTheme.MONITOR_CAPTION);
        lbVideo.setFont(lbVideo.getFont().deriveFont(Font.PLAIN, 12f));
        lbVideo.setBorder(BorderFactory.createLineBorder(WorkflowUiTheme.MONITOR_BORDER, 1));
        lbVideo.setHorizontalAlignment(SwingConstants.CENTER);
        lbVideo.setVerticalAlignment(SwingConstants.CENTER);

        add(header, BorderLayout.NORTH);
        add(lbVideo, BorderLayout.CENTER);

        addHierarchyListener(new HierarchyListener() {
            @Override
            public void hierarchyChanged(HierarchyEvent e) {
                if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) {
                    if (isShowing() && liveDesired) {
                        startLivePreview();
                    } else if (!isShowing()) {
                        detachStream(false);
                    }
                }
            }
        });

        keepAliveTimer = new Timer(2000, e -> {
            if (!liveDesired || starting || !isShowing()) {
                return;
            }
            if (!CameraHardware.isPreviewRunning()) {
                startLivePreview();
            }
        });
        keepAliveTimer.setRepeats(true);
    }

    public void startLivePreview() {
        liveDesired = true;
        if (!keepAliveTimer.isRunning()) {
            keepAliveTimer.start();
        }
        if (!isShowing() || starting) {
            setPlaceholder("Iniciando vídeo...");
            return;
        }
        if (CameraHardware.isPreviewRunning()) {
            CameraFrameStream.getInstance().addListener(this);
            return;
        }
        starting = true;
        setPlaceholder("Iniciando vídeo...");
        btnRecalibrate.setEnabled(false);
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
                starting = false;
                btnRecalibrate.setEnabled(true);
                try {
                    String error = get();
                    if (error != null) {
                        setPlaceholder(shortStatus(error));
                    }
                } catch (Exception e) {
                    setPlaceholder("Falha no vídeo");
                }
            }
        }.execute();
    }

    public void stopLivePreview() {
        liveDesired = false;
        keepAliveTimer.stop();
        starting = false;
        detachStream(true);
        setPlaceholder("Vídeo parado");
        btnRecalibrate.setEnabled(true);
    }

    public void ensureLivePreview() {
        if (!liveDesired || !CameraHardware.isPreviewRunning()) {
            startLivePreview();
        } else {
            CameraFrameStream.getInstance().addListener(this);
        }
    }

    private void detachStream(boolean stopProcess) {
        CameraFrameStream.getInstance().removeListener(this);
        if (stopProcess) {
            CameraHardware.stopPreview();
        }
        currentIcon = null;
        lbVideo.setIcon(null);
    }

    private void recalibrate() {
        btnRecalibrate.setEnabled(false);
        boolean wasLive = liveDesired;
        CameraHardware.stopPreview();
        setPlaceholder("Recalibrando...");
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                CameraMicroserviceLifecycle lifecycle = CameraMicroserviceLifecycle.getInstance();
                CameraMicroserviceClient client = lifecycle.getClient();
                if (!client.checkHealth()) {
                    lifecycle.start();
                    client.checkHealth();
                }
                if (client.isAvailable()) {
                    return client.recalibrate();
                }
                return CameraHardware.recalibrate();
            }

            @Override
            protected void done() {
                btnRecalibrate.setEnabled(true);
                try {
                    String msg = get();
                    setPlaceholder("Câmera recalibrada");
                    Window owner = SwingUtilities.getWindowAncestor(CameraLiveMonitorPanel.this);
                    JOptionPane.showMessageDialog(owner, msg,
                            "Recalibrar câmera", JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    setPlaceholder("Recalibração falhou");
                    Window owner = SwingUtilities.getWindowAncestor(CameraLiveMonitorPanel.this);
                    JOptionPane.showMessageDialog(owner,
                            "Erro na recalibração: " + cause.getMessage(),
                            "Recalibrar câmera", JOptionPane.ERROR_MESSAGE);
                }
                if (wasLive) {
                    startLivePreview();
                }
            }
        }.execute();
    }

    @Override
    public void onFrame(BufferedImage frame) {
        if (frame == null) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            if (!liveDesired || !isShowing()) {
                return;
            }
            Image scaled = scaleToFit(frame, lbVideo.getWidth(), lbVideo.getHeight());
            currentIcon = new ImageIcon(scaled);
            lbVideo.setText(null);
            lbVideo.setIcon(currentIcon);
        });
    }

    @Override
    public void onStatus(String message, boolean error) {
        SwingUtilities.invokeLater(() -> {
            if (lbVideo.getIcon() == null) {
                setPlaceholder(message != null ? message : (error ? "Erro na câmera" : ""));
            }
        });
    }

    private void setPlaceholder(String text) {
        lbVideo.setIcon(null);
        lbVideo.setText(text != null ? text : "");
        lbVideo.setForeground(WorkflowUiTheme.MONITOR_CAPTION);
    }

    private static String shortStatus(String error) {
        if (error == null || error.isEmpty()) {
            return "Câmera indisponível";
        }
        if (error.length() <= 48) {
            return error;
        }
        return error.substring(0, 45) + "...";
    }

    private static Image scaleToFit(BufferedImage source, int maxW, int maxH) {
        int w = Math.max(1, maxW);
        int h = Math.max(1, maxH);
        if (w < 8 || h < 8) {
            return source;
        }
        double scale = Math.min((double) w / source.getWidth(), (double) h / source.getHeight());
        int tw = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int th = Math.max(1, (int) Math.round(source.getHeight() * scale));
        return source.getScaledInstance(tw, th, Image.SCALE_FAST);
    }
}
