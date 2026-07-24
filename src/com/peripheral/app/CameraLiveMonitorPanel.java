package com.peripheral.app;

import com.peripheral.camera.CameraHardware;
import com.peripheral.camera.CameraMicroserviceClient;
import com.peripheral.camera.CameraMicroserviceLifecycle;
import com.peripheral.camera.CameraServiceException;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.HierarchyEvent;
import java.awt.event.HierarchyListener;

/**
 * Monitor de vídeo da câmera no estilo do monitor da balança.
 * No Raspberry Pi, o preview nativo do rpicam é posicionado sobre a área de vídeo.
 */
public class CameraLiveMonitorPanel extends JPanel {

    private final JLabel lbCaption = new JLabel("MONITOR DA CÂMERA");
    private final JLabel lbStatus = new JLabel("Aguardando início do fluxo...", SwingConstants.CENTER);
    private final JPanel videoHost = new JPanel(new BorderLayout());
    private final ThemedButton btnRecalibrate =
            WorkflowUiTheme.button("Recalibrar", ThemedButton.Variant.SECONDARY)
                    .withSize(ThemedButton.Size.SMALL);

    private final Timer keepAliveTimer;
    private boolean liveDesired;
    private boolean starting;
    private String lastGeometry;

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

        videoHost.setOpaque(true);
        videoHost.setBackground(new Color(0x08, 0x0F, 0x1C));
        videoHost.setBorder(BorderFactory.createLineBorder(WorkflowUiTheme.MONITOR_BORDER, 1));

        lbStatus.setFont(lbStatus.getFont().deriveFont(Font.PLAIN, 12f));
        lbStatus.setForeground(WorkflowUiTheme.MONITOR_CAPTION);
        videoHost.add(lbStatus, BorderLayout.CENTER);

        add(header, BorderLayout.NORTH);
        add(videoHost, BorderLayout.CENTER);

        ComponentAdapter boundsListener = new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                syncPreviewIfNeeded(false);
            }

            @Override
            public void componentMoved(ComponentEvent e) {
                syncPreviewIfNeeded(false);
            }
        };
        addComponentListener(boundsListener);
        videoHost.addComponentListener(boundsListener);
        addHierarchyListener(new HierarchyListener() {
            @Override
            public void hierarchyChanged(HierarchyEvent e) {
                if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) {
                    if (isShowing() && liveDesired) {
                        startLivePreview();
                    } else if (!isShowing()) {
                        stopLivePreview();
                    }
                }
            }
        });

        keepAliveTimer = new Timer(1500, e -> {
            if (!liveDesired || starting || !isShowing()) {
                return;
            }
            if (!CameraHardware.isPreviewRunning()) {
                startLivePreview();
            } else {
                syncPreviewIfNeeded(false);
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
            setStatus("Iniciando vídeo...", WorkflowUiTheme.MONITOR_CAPTION);
            return;
        }
        Rectangle bounds = screenBoundsOf(videoHost);
        if (bounds.width < 40 || bounds.height < 40) {
            setStatus("Aguardando layout da câmera...", WorkflowUiTheme.MONITOR_CAPTION);
            SwingUtilities.invokeLater(() -> {
                if (liveDesired) {
                    startLivePreview();
                }
            });
            return;
        }
        String geometry = bounds.x + "," + bounds.y + "," + bounds.width + "," + bounds.height;
        if (CameraHardware.isPreviewRunning() && geometry.equals(lastGeometry)) {
            setStatus("Vídeo ao vivo", WorkflowUiTheme.MONITOR_VALUE);
            return;
        }
        starting = true;
        setStatus("Iniciando vídeo...", WorkflowUiTheme.MONITOR_CAPTION);
        btnRecalibrate.setEnabled(false);
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                try {
                    CameraHardware.startPreview(bounds.x, bounds.y, bounds.width, bounds.height);
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
                        lastGeometry = null;
                        setStatus(shortStatus(error), WorkflowUiTheme.MONITOR_ALERT);
                    } else {
                        lastGeometry = geometry;
                        setStatus("Vídeo ao vivo", WorkflowUiTheme.MONITOR_VALUE);
                    }
                } catch (Exception e) {
                    lastGeometry = null;
                    setStatus("Falha no vídeo", WorkflowUiTheme.MONITOR_ALERT);
                }
            }
        }.execute();
    }

    public void stopLivePreview() {
        liveDesired = false;
        keepAliveTimer.stop();
        lastGeometry = null;
        starting = false;
        CameraHardware.stopPreview();
        setStatus("Vídeo parado", WorkflowUiTheme.MONITOR_CAPTION);
        btnRecalibrate.setEnabled(true);
    }

    public void ensureLivePreview() {
        if (!liveDesired) {
            startLivePreview();
        } else if (!CameraHardware.isPreviewRunning()) {
            startLivePreview();
        } else {
            syncPreviewIfNeeded(true);
        }
    }

    private void syncPreviewIfNeeded(boolean forceRestart) {
        if (!liveDesired || starting || !isShowing()) {
            return;
        }
        Rectangle bounds = screenBoundsOf(videoHost);
        if (bounds.width < 40 || bounds.height < 40) {
            return;
        }
        String geometry = bounds.x + "," + bounds.y + "," + bounds.width + "," + bounds.height;
        if (!forceRestart && geometry.equals(lastGeometry) && CameraHardware.isPreviewRunning()) {
            return;
        }
        startLivePreview();
    }

    private void recalibrate() {
        btnRecalibrate.setEnabled(false);
        boolean wasLive = liveDesired;
        CameraHardware.stopPreview();
        lastGeometry = null;
        setStatus("Recalibrando...", WorkflowUiTheme.MONITOR_CAPTION);
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
                    setStatus("Câmera recalibrada", WorkflowUiTheme.MONITOR_VALUE);
                    Window owner = SwingUtilities.getWindowAncestor(CameraLiveMonitorPanel.this);
                    JOptionPane.showMessageDialog(owner, msg,
                            "Recalibrar câmera", JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    setStatus("Recalibração falhou", WorkflowUiTheme.MONITOR_ALERT);
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

    private void setStatus(String text, Color color) {
        lbStatus.setText(text != null ? text : "");
        lbStatus.setForeground(color != null ? color : WorkflowUiTheme.MONITOR_CAPTION);
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

    private static Rectangle screenBoundsOf(Component component) {
        try {
            if (!component.isShowing()) {
                return new Rectangle();
            }
            Point location = component.getLocationOnScreen();
            Dimension size = component.getSize();
            return new Rectangle(location.x, location.y, size.width, size.height);
        } catch (IllegalComponentStateException e) {
            return new Rectangle();
        }
    }
}
