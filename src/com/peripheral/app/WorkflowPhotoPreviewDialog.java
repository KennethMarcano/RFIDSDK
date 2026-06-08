package com.peripheral.app;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.io.File;
import java.nio.file.Files;

public class WorkflowPhotoPreviewDialog extends JDialog {

    private static final int MAX_IMAGE_SIZE = 640;

    public WorkflowPhotoPreviewDialog(Window owner, File photoFile) {
        super(owner, "Foto capturada", ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        JPanel content = new JPanel(new BorderLayout(12, 12));
        content.setOpaque(false);
        content.setBorder(WorkflowUiTheme.empty(16, 16, 16, 16));

        JLabel imageLabel = new JLabel("", SwingConstants.CENTER);
        imageLabel.setPreferredSize(new Dimension(MAX_IMAGE_SIZE, MAX_IMAGE_SIZE));
        imageLabel.setOpaque(true);
        imageLabel.setBackground(WorkflowUiTheme.BG_CARD);
        imageLabel.setForeground(WorkflowUiTheme.TEXT_SECONDARY);
        imageLabel.setBorder(BorderFactory.createLineBorder(WorkflowUiTheme.BORDER));

        if (photoFile != null && photoFile.isFile() && photoFile.length() > 0) {
            ImageIcon icon = new ImageIcon(photoFile.getAbsolutePath());
            if (icon.getIconWidth() > 0 && icon.getIconHeight() > 0) {
                Image scaled = scaleImage(icon.getImage(), MAX_IMAGE_SIZE, MAX_IMAGE_SIZE);
                imageLabel.setIcon(new ImageIcon(scaled));
            } else {
                imageLabel.setText("Não foi possível carregar a imagem.");
            }
        } else {
            imageLabel.setText("Arquivo de foto não encontrado ou vazio.");
        }

        JScrollPane scroll = new JScrollPane(imageLabel);
        WorkflowUiTheme.styleScrollPane(scroll);
        content.add(scroll, BorderLayout.CENTER);

        ThemedButton btnClose = WorkflowUiTheme.button("Fechar", ThemedButton.Variant.SECONDARY);
        btnClose.addActionListener(e -> dispose());
        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        south.setOpaque(false);
        south.add(btnClose);
        content.add(south, BorderLayout.SOUTH);

        getContentPane().setBackground(WorkflowUiTheme.BG_PAGE);
        setContentPane(content);
        getRootPane().setDefaultButton(btnClose);
        getRootPane().registerKeyboardAction(
                e -> dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        pack();
        setMinimumSize(new Dimension(Math.max(420, getWidth()), Math.max(320, getHeight())));
        setLocationRelativeTo(owner);
    }

    public static void showPreview(Window owner, String photoPath) {
        if (photoPath == null || photoPath.trim().isEmpty()) {
            JOptionPane.showMessageDialog(owner,
                    "Nenhuma foto disponível para este ciclo.",
                    "Foto", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        File file = new File(photoPath);
        if (!file.isFile() || file.length() == 0) {
            JOptionPane.showMessageDialog(owner,
                    "Arquivo de foto não encontrado.",
                    "Foto", JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            WorkflowPhotoPreviewDialog dialog = new WorkflowPhotoPreviewDialog(owner, file);
            dialog.setVisible(true);
        } catch (Exception ex) {
            tryOpenWithDesktop(owner, file);
        }
    }

    private static void tryOpenWithDesktop(Window owner, File file) {
        if (!Desktop.isDesktopSupported()) {
            JOptionPane.showMessageDialog(owner,
                    "Não foi possível abrir a foto.",
                    "Foto", JOptionPane.ERROR_MESSAGE);
            return;
        }
        try {
            if (Desktop.getDesktop().isSupported(Desktop.Action.OPEN) && Files.isReadable(file.toPath())) {
                Desktop.getDesktop().open(file);
            } else {
                JOptionPane.showMessageDialog(owner,
                        "Não foi possível abrir a foto.",
                        "Foto", JOptionPane.ERROR_MESSAGE);
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(owner,
                    "Erro ao abrir foto: " + ex.getMessage(),
                    "Foto", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static Image scaleImage(Image source, int maxWidth, int maxHeight) {
        int width = source.getWidth(null);
        int height = source.getHeight(null);
        if (width <= 0 || height <= 0) {
            return source;
        }
        double scale = Math.min((double) maxWidth / width, (double) maxHeight / height);
        if (scale >= 1.0) {
            return source;
        }
        int newWidth = Math.max(1, (int) (width * scale));
        int newHeight = Math.max(1, (int) (height * scale));
        return source.getScaledInstance(newWidth, newHeight, Image.SCALE_SMOOTH);
    }
}
