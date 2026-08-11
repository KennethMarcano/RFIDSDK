package com.peripheral.workflow.label;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class PdfLabelGenerator {

    public Path generate(LabelContent content, float widthMm, float heightMm,
                         Path sessionDirectory, int labelIndex) throws IOException {
        if (sessionDirectory == null) {
            throw new IOException("Diretório da sessão não definido");
        }
        Files.createDirectories(sessionDirectory);

        LabelContent data = content != null ? content : new LabelContent("", 1, null, 0, "");
        float widthPt = LabelLayout.mmToPoints(widthMm);
        float heightPt = LabelLayout.mmToPoints(heightMm);
        Path outputFile = sessionDirectory.resolve(String.format("label_%03d.pdf", Math.max(1, labelIndex)));

        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(widthPt, heightPt));
            document.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                float margin = LabelLayout.mmToPoints(4f);
                float y = heightPt - margin - 18f;

                cs.beginText();
                cs.setFont(PDType1Font.HELVETICA_BOLD, 16);
                cs.newLineAtOffset(margin, y);
                cs.showText(pdfSafe("Pedido " + (data.getOrderNumber().isEmpty() ? "-" : data.getOrderNumber())));
                cs.endText();

                y -= 18;
                cs.beginText();
                cs.setFont(PDType1Font.HELVETICA, 11);
                cs.newLineAtOffset(margin, y);
                cs.showText(pdfSafe("Volume " + data.getVolumeIndex()));
                cs.endText();

                y -= 16;
                cs.setStrokingColor(0.7f);
                cs.moveTo(margin, y);
                cs.lineTo(widthPt - margin, y);
                cs.stroke();
                y -= 16;

                cs.setFont(PDType1Font.HELVETICA, 10);
                for (LabelContent.Line line : data.getLines()) {
                    String row = line.codigo + "   x" + line.quantidade
                            + "   " + LabelLayout.formatWeight(line.pesoLinhaKg);
                    cs.beginText();
                    cs.newLineAtOffset(margin, y);
                    cs.showText(pdfSafe(row));
                    cs.endText();
                    y -= 14;
                }

                y -= 6;
                cs.beginText();
                cs.setFont(PDType1Font.HELVETICA_BOLD, 12);
                cs.newLineAtOffset(margin, y);
                cs.showText(pdfSafe("Peso conferido: "
                        + LabelLayout.formatWeight(data.getMeasuredWeightKg())));
                cs.endText();

                boolean[][] modules = QrMatrix.encode(data.getQrPayload());
                BufferedImage qrImage = QrMatrix.toImage(modules, 3, 2);
                PDImageXObject qr = LosslessFactory.createFromImage(document, qrImage);
                float qrSize = Math.min(LabelLayout.mmToPoints(28f), y - margin - 4f);
                float qrX = (widthPt - qrSize) / 2f;
                float qrY = margin;
                cs.drawImage(qr, qrX, qrY, qrSize, qrSize);
            }
            document.save(outputFile.toFile());
        }
        return outputFile.toAbsolutePath();
    }

    private static String pdfSafe(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(' || c == ')' || c == '\\') {
                sb.append(' ');
            } else if (c >= 32 && c < 127) {
                sb.append(c);
            } else {
                sb.append(' ');
            }
        }
        return sb.toString();
    }
}
