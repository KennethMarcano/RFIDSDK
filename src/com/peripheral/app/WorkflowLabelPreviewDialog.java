package com.peripheral.app;

import javax.swing.JOptionPane;
import java.awt.Desktop;
import java.awt.Window;
import java.io.File;
import java.nio.file.Files;

public final class WorkflowLabelPreviewDialog {

    private WorkflowLabelPreviewDialog() {
    }

    public static void showPreview(Window owner, String pdfPath) {
        if (pdfPath == null || pdfPath.trim().isEmpty()) {
            JOptionPane.showMessageDialog(owner,
                    "Nenhuma etiqueta PDF disponível para esta leitura.",
                    "Etiqueta", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        File file = new File(pdfPath);
        if (!file.isFile() || file.length() == 0) {
            JOptionPane.showMessageDialog(owner,
                    "Arquivo PDF da etiqueta não encontrado.",
                    "Etiqueta", JOptionPane.WARNING_MESSAGE);
            return;
        }
        openPdf(owner, file);
    }

    private static void openPdf(Window owner, File file) {
        if (file == null || !file.isFile()) {
            JOptionPane.showMessageDialog(owner,
                    "PDF da etiqueta não encontrado.",
                    "Etiqueta", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!Desktop.isDesktopSupported()) {
            JOptionPane.showMessageDialog(owner,
                    "Abrir PDF não suportado neste ambiente.",
                    "Etiqueta", JOptionPane.ERROR_MESSAGE);
            return;
        }
        try {
            if (Desktop.getDesktop().isSupported(Desktop.Action.OPEN) && Files.isReadable(file.toPath())) {
                Desktop.getDesktop().open(file);
            } else {
                JOptionPane.showMessageDialog(owner,
                        "Não foi possível abrir o PDF.",
                        "Etiqueta", JOptionPane.ERROR_MESSAGE);
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(owner,
                    "Erro ao abrir PDF: " + ex.getMessage(),
                    "Etiqueta", JOptionPane.ERROR_MESSAGE);
        }
    }
}
