package com.peripheral.app;

import com.peripheral.camera.CameraHardware;
import com.peripheral.camera.CameraMicroserviceClient;
import com.peripheral.camera.CameraMicroserviceLifecycle;
import com.peripheral.camera.CameraServiceException;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.function.Consumer;

/**
 * Diálogo para testar a câmera Sony IMX500 — abre preview de vídeo nativo via rpicam.
 */
public class CameraTestDialog extends JDialog {

    private final Consumer<String> logConsumer;
    private final JLabel lbStatus = new JLabel("Verificando câmera...");
    private final JTextArea taDetails = new JTextArea(8, 48);
    private final ThemedButton btnOpenPreview =
            WorkflowUiTheme.button("Abrir vídeo (preview)", ThemedButton.Variant.PRIMARY);
    private final ThemedButton btnClosePreview =
            WorkflowUiTheme.button("Fechar preview", ThemedButton.Variant.DANGER);
    private final ThemedButton btnRefresh =
            WorkflowUiTheme.button("Atualizar status", ThemedButton.Variant.SECONDARY);
    private final javax.swing.Timer pollTimer;

    public CameraTestDialog(Window owner, Consumer<String> logConsumer) {
        super(owner, "Testar câmera — Sony IMX500", ModalityType.MODELESS);
        this.logConsumer = logConsumer;

        JPanel content = new JPanel(new BorderLayout(0, 12));
        content.setOpaque(false);
        content.setBorder(WorkflowUiTheme.empty(16, 16, 16, 16));

        JLabel hint = WorkflowUiTheme.createHintLabel(
                "<html>Use <b>Abrir vídeo</b> para iniciar o preview contínuo da câmera "
                        + "(<code>rpicam-hello --timeout 0</code>). "
                        + "Uma janela nativa com o vídeo será aberta no Raspberry Pi.</html>");
        content.add(hint, BorderLayout.NORTH);

        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));

        styleCameraStatusPill(false);
        lbStatus.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(lbStatus);
        body.add(Box.createVerticalStrut(10));

        taDetails.setEditable(false);
        taDetails.setLineWrap(true);
        taDetails.setWrapStyleWord(true);
        taDetails.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        taDetails.setBackground(WorkflowUiTheme.CHIP_BG);
        taDetails.setForeground(WorkflowUiTheme.TEXT_PRIMARY);
        taDetails.setBorder(WorkflowUiTheme.empty(8, 8, 8, 8));
        JScrollPane scroll = new JScrollPane(taDetails);
        WorkflowUiTheme.styleScrollPane(scroll);
        scroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        scroll.setPreferredSize(new Dimension(520, 180));
        body.add(scroll);

        JPanel section = WorkflowUiTheme.createSection("Câmera", body);
        content.add(section, BorderLayout.CENTER);

        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        south.setOpaque(false);
        ThemedButton btnDone = WorkflowUiTheme.button("Concluído", ThemedButton.Variant.SECONDARY);
        btnDone.addActionListener(e -> closeDialog());
        south.add(btnRefresh);
        south.add(btnClosePreview);
        south.add(btnOpenPreview);
        south.add(btnDone);
        content.add(south, BorderLayout.SOUTH);

        btnRefresh.addActionListener(e -> refreshStatus());
        btnOpenPreview.addActionListener(e -> openPreview());
        btnClosePreview.addActionListener(e -> closePreview());

        getContentPane().setBackground(WorkflowUiTheme.BG_PAGE);
        setContentPane(content);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                pollTimer.stop();
                CameraHardware.stopPreview();
            }
        });

        pollTimer = new javax.swing.Timer(1000, e -> updatePreviewButtons());
        pack();
        setMinimumSize(new Dimension(560, 420));
        setLocationRelativeTo(owner);
    }

    public void showDialog() {
        refreshStatus();
        updatePreviewButtons();
        pollTimer.start();
        setVisible(true);
        toFront();
    }

    private void refreshStatus() {
        btnRefresh.setEnabled(false);
        lbStatus.setText("Câmera: verificando...");
        styleCameraStatusPill(false);
        taDetails.setText("Consultando hardware e serviço...");

        new SwingWorker<StatusSnapshot, Void>() {
            @Override
            protected StatusSnapshot doInBackground() {
                CameraHardware.invalidateCache();
                boolean rpicam = CameraHardware.isRpicamAvailable();
                boolean present = rpicam && CameraHardware.isCameraPresent();
                String details = CameraHardware.describeCamera();
                CameraMicroserviceClient client = CameraMicroserviceLifecycle.getInstance().getClient();
                boolean service = client.checkHealth();
                String serviceExtra = "";
                if (service) {
                    try {
                        CameraMicroserviceClient.CameraStatus st = client.getStatus();
                        serviceExtra = "Serviço HTTP: online"
                                + (st.isReady() ? " (ready)" : " (não ready)")
                                + (st.getLastError() != null && !st.getLastError().isEmpty()
                                ? " — " + st.getLastError() : "");
                    } catch (Exception e) {
                        serviceExtra = "Serviço HTTP: online (status detalhado indisponível)";
                    }
                } else {
                    String err = CameraMicroserviceLifecycle.getInstance().getLastStartupError();
                    serviceExtra = "Serviço HTTP: indisponível"
                            + (err != null && !err.isEmpty() ? " — " + err : "");
                }
                return new StatusSnapshot(rpicam, present, service, details, serviceExtra);
            }

            @Override
            protected void done() {
                btnRefresh.setEnabled(true);
                try {
                    StatusSnapshot snap = get();
                    boolean ok = snap.cameraPresent || snap.serviceOnline;
                    if (snap.cameraPresent) {
                        lbStatus.setText("Câmera online — Sony IMX500");
                    } else if (snap.serviceOnline) {
                        lbStatus.setText("Câmera online — serviço");
                    } else if (snap.rpicamAvailable) {
                        lbStatus.setText("Câmera indisponível — rpicam ok, sensor não detectado");
                    } else {
                        lbStatus.setText("Câmera indisponível");
                    }
                    styleCameraStatusPill(ok);
                    taDetails.setText(snap.details + "\n\n" + snap.serviceInfo
                            + "\n\nPreview: use timeout 0 (sem encerrar) via rpicam-hello.");
                    log("Status câmera: " + lbStatus.getText());
                } catch (Exception e) {
                    lbStatus.setText("Câmera: erro na verificação");
                    styleCameraStatusPill(false);
                    taDetails.setText(e.getMessage());
                }
                updatePreviewButtons();
            }
        }.execute();
    }

    private void openPreview() {
        btnOpenPreview.setEnabled(false);
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
                        JOptionPane.showMessageDialog(CameraTestDialog.this, error,
                                "Preview", JOptionPane.ERROR_MESSAGE);
                        log("ERRO preview câmera: " + error);
                    } else {
                        log("Preview da câmera iniciado (rpicam-hello --timeout 0)");
                    }
                } catch (Exception e) {
                    JOptionPane.showMessageDialog(CameraTestDialog.this, e.getMessage(),
                            "Preview", JOptionPane.ERROR_MESSAGE);
                }
                updatePreviewButtons();
            }
        }.execute();
    }

    private void closePreview() {
        CameraHardware.stopPreview();
        log("Preview da câmera encerrado");
        updatePreviewButtons();
    }

    private void updatePreviewButtons() {
        boolean running = CameraHardware.isPreviewRunning();
        btnOpenPreview.setEnabled(!running);
        btnClosePreview.setEnabled(running);
    }

    private void closeDialog() {
        pollTimer.stop();
        CameraHardware.stopPreview();
        dispose();
    }

    private void styleCameraStatusPill(boolean online) {
        if (online) {
            WorkflowUiTheme.styleStatusPill(lbStatus,
                    new Color(0xD1, 0xFA, 0xE5), WorkflowUiTheme.SUCCESS);
        } else {
            WorkflowUiTheme.styleStatusPill(lbStatus,
                    new Color(0xFE, 0xF3, 0xC7), WorkflowUiTheme.WARNING);
        }
    }

    private void log(String message) {
        if (logConsumer != null) {
            logConsumer.accept(message);
        }
    }

    private static final class StatusSnapshot {
        final boolean rpicamAvailable;
        final boolean cameraPresent;
        final boolean serviceOnline;
        final String details;
        final String serviceInfo;

        StatusSnapshot(boolean rpicamAvailable, boolean cameraPresent, boolean serviceOnline,
                       String details, String serviceInfo) {
            this.rpicamAvailable = rpicamAvailable;
            this.cameraPresent = cameraPresent;
            this.serviceOnline = serviceOnline;
            this.details = details != null ? details : "";
            this.serviceInfo = serviceInfo != null ? serviceInfo : "";
        }
    }
}
