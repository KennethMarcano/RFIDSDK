package com.peripheral.workflow.label;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
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
                drawLabel(document, cs, data, widthPt, heightPt);
            }
            document.save(outputFile.toFile());
        }
        return outputFile.toAbsolutePath();
    }

    private void drawLabel(PDDocument document, PDPageContentStream cs,
                           LabelContent data, float widthPt, float heightPt) throws IOException {
        float margin = LabelLayout.mmToPoints(3f);
        float accentH = LabelLayout.mmToPoints(2.4f);

        cs.setNonStrokingColor(0f);
        cs.addRect(0, heightPt - accentH, widthPt, accentH);
        cs.fill();

        float logoW = LabelLayout.mmToPoints(32f);
        float logoH = LabelLayout.mmToPoints(16f);
        float headerTop = heightPt - accentH - 4f;
        float logoY = headerTop - logoH;
        float textX = margin;

        BufferedImage logo = LabelAssets.loadLogo();
        if (logo != null) {
            PDImageXObject logoX = LosslessFactory.createFromImage(document, logo);
            float scale = Math.min(logoW / logo.getWidth(), logoH / logo.getHeight());
            float drawW = logo.getWidth() * scale;
            float drawH = logo.getHeight() * scale;
            cs.drawImage(logoX, margin, logoY + (logoH - drawH) / 2f, drawW, drawH);
            textX = margin + logoW + 8f;
        } else {
            drawText(cs, PDType1Font.HELVETICA_BOLD, 18, margin, logoY + 14, "eship", 0f);
            textX = margin + LabelLayout.mmToPoints(22f);
        }

        String order = data.getOrderNumber().isEmpty() ? "-" : data.getOrderNumber();
        float orderSize = order.length() <= 6 ? 26f : order.length() <= 10 ? 20f : 16f;
        drawText(cs, PDType1Font.HELVETICA_BOLD, 9, textX, headerTop - 12, "PEDIDO", 0f);
        drawText(cs, PDType1Font.HELVETICA_BOLD, orderSize, textX, logoY + 4, pdfSafe(order), 0f);

        float volBoxW = LabelLayout.mmToPoints(24f);
        float volX = widthPt - margin - volBoxW;
        cs.setNonStrokingColor(0f);
        cs.addRect(volX, logoY, volBoxW, logoH);
        cs.fill();
        drawText(cs, PDType1Font.HELVETICA_BOLD, 8, volX + 5, headerTop - 14, "VOL", 1f);
        String volume = String.valueOf(data.getVolumeIndex());
        drawText(cs, PDType1Font.HELVETICA_BOLD, 18, volX + 5, logoY + 8, volume, 1f);

        float barY = logoY - 8f;
        cs.setNonStrokingColor(0f);
        cs.addRect(margin, barY, widthPt - 2 * margin, 3.5f);
        cs.fill();

        float qrSize = LabelLayout.mmToPoints(40f);
        float qrX = widthPt - margin - qrSize;
        float qrY = barY - 10f - qrSize;
        if (qrY < margin + 14f) {
            qrY = margin + 14f;
            qrSize = Math.max(LabelLayout.mmToPoints(28f), barY - 10f - qrY);
            qrX = widthPt - margin - qrSize;
        }

        float areaX = qrX - 3f;
        float areaY = qrY - 3f;
        float areaSize = qrSize + 6f;
        boolean[][] modules = QrMatrix.encode(data.getQrPayload());
        BufferedImage qrImage = QrMatrix.toImage(modules, 4, 3);
        PDImageXObject qr = LosslessFactory.createFromImage(document, qrImage);
        float drawSize = qrSize;
        float qrDrawX = areaX + (areaSize - drawSize) / 2f;
        float qrDrawY = areaY + (areaSize - drawSize) / 2f;
        cs.drawImage(qr, qrDrawX, qrDrawY, drawSize, drawSize);
        float captionY = Math.max(margin, areaY - 12f);
        float captionWidth = stringWidth(PDType1Font.HELVETICA_BOLD, 8, "ESCANEIE O QR");
        drawText(cs, PDType1Font.HELVETICA_BOLD, 8,
                areaX + Math.max(0f, (areaSize - captionWidth) / 2f),
                captionY, "ESCANEIE O QR", 0f);

        float y = barY - 16f;
        drawText(cs, PDType1Font.HELVETICA_BOLD, 10, margin, y, "PRODUTOS", 0f);
        y -= 16f;

        float weightBlockBottom = margin + 8f;
        float weightTop = weightBlockBottom + 42f;
        int printed = 0;
        for (LabelContent.Line line : data.getLines()) {
            if (y - 28f < weightTop + 8f) {
                drawText(cs, PDType1Font.HELVETICA_BOLD, 11, margin, y, "...", 0f);
                break;
            }
            drawText(cs, PDType1Font.HELVETICA_BOLD, 14, margin, y, pdfSafe(line.codigo), 0f);
            y -= 16f;
            String detail = "x" + line.quantidade + "   " + LabelLayout.formatWeight(line.pesoLinhaKg);
            drawText(cs, PDType1Font.HELVETICA, 12, margin, y, pdfSafe(detail), 0f);
            y -= 16f;
            printed++;
        }
        if (printed == 0) {
            drawText(cs, PDType1Font.HELVETICA, 12, margin, y, "-", 0f);
        }

        float leftW = Math.max(80f, qrX - margin - 10f);
        cs.setNonStrokingColor(0f);
        cs.addRect(margin, weightTop + 28f, leftW, 2.8f);
        cs.fill();
        drawText(cs, PDType1Font.HELVETICA_BOLD, 10, margin, weightTop + 14f, "PESO CONFERIDO", 0f);
        drawText(cs, PDType1Font.HELVETICA_BOLD, 22, margin, weightBlockBottom,
                pdfSafe(LabelLayout.formatWeight(data.getMeasuredWeightKg())), 0f);
    }

    private static float stringWidth(PDFont font, float size, String text) throws IOException {
        String safe = pdfSafe(text);
        if (safe.isEmpty()) {
            return 0f;
        }
        return font.getStringWidth(safe) / 1000f * size;
    }

    private static void drawText(PDPageContentStream cs, PDFont font, float size,
                                 float x, float y, String text, float gray) throws IOException {
        cs.setNonStrokingColor(gray);
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(pdfSafe(text));
        cs.endText();
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
