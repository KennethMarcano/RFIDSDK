package com.peripheral.app;

import com.peripheral.camera.CameraFrameStream;
import com.peripheral.camera.CameraHardware;
import com.peripheral.camera.CameraServiceException;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.util.function.Consumer;

/**
 * Teste rápido da câmera: abre o mini-view de vídeo imediatamente, sem detalhes técnicos.
 */
public class CameraTestDialog extends JDialog implements CameraFrameStream.Listener {

    private final Consumer<String> logConsumer;
    private final JLabel lbVideo = new JLabel("Abrindo câmera...", SwingConstants.CENTER);
    private final ThemedButton btnClose =
            WorkflowUiTheme.button("Fechar", ThemedButton.Variant.SECONDARY);

    public CameraTestDialog(Window owner, Consumer<String> logConsumer) {
        super(owner, "Câmera", ModalityType.MODELESS);
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
        lbVideo.setPreferredSize(new Dimension(520, 320));
        lbVideo.setHorizontalAlignment(SwingConstants.CENTER);
        lbVideo.setVerticalAlignment(SwingConstants.CENTER);

        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        south.setOpaque(false);
        btnClose.addActionListener(e -> closeDialog());
        south.add(btnClose);

        content.add(lbVideo, BorderLayout.CENTER);
        content.add(south, BorderLayout.SOUTH);

        setContentPane(content);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                startVideoNow();
            }

            @Override
            public void windowClosed(WindowEvent e) {
                stopVideo();
            }
        });
        pack();
        WorkflowUiTheme.clampToScreen(this, owner);
    }

    public void showDialog() {
        setVisible(true);
        toFront();
        startVideoNow();
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
                        log("Vídeo da câmera iniciado");
                    }
                } catch (Exception e) {
                    lbVideo.setText(e.getMessage());
                }
            }
        }.execute();
    }

    private void stopVideo() {
        CameraFrameStream.getInstance().removeListener(this);
        CameraHardware.stopPreview();
        lbVideo.setIcon(null);
    }

    private void closeDialog() {
        stopVideo();
        dispose();
    }

    @Override
    public void onFrame(BufferedImage frame) {
        if (frame == null) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            if (!isDisplayable()) {
                return;
            }
            int maxW = Math.max(1, lbVideo.getWidth());
            int maxH = Math.max(1, lbVideo.getHeight());
            double scale = Math.min((double) maxW / frame.getWidth(), (double) maxH / frame.getHeight());
            int tw = Math.max(1, (int) Math.round(frame.getWidth() * scale));
            int th = Math.max(1, (int) Math.round(frame.getHeight() * scale));
            Image scaled = frame.getScaledInstance(tw, th, Image.SCALE_FAST);
            lbVideo.setText(null);
            lbVideo.setIcon(new ImageIcon(scaled));
        });
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
