package com.peripheral.workflow.label;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class ZplLabelGenerator {

    public Path generate(LabelContent content, float widthMm, float heightMm,
                         Path sessionDirectory, int labelIndex) throws java.io.IOException {
        if (sessionDirectory == null) {
            throw new java.io.IOException("Diretório da sessão não definido");
        }
        Files.createDirectories(sessionDirectory);
        Path outputFile = sessionDirectory.resolve(String.format("label_%03d.zpl", Math.max(1, labelIndex)));
        String zpl = buildZpl(content, widthMm, heightMm);
        Files.write(outputFile, zpl.getBytes(StandardCharsets.US_ASCII));
        return outputFile.toAbsolutePath();
    }

    public String buildZpl(LabelContent content, float widthMm, float heightMm) {
        LabelContent data = content != null ? content : new LabelContent("", 1, null, 0, "");
        int dpi = LabelLayout.ZPL_DPI;
        int pw = LabelLayout.mmToDots(widthMm, dpi);
        int ll = LabelLayout.mmToDots(heightMm, dpi);
        int m = LabelLayout.mmToDots(3f, dpi);

        String order = LabelLayout.zplEscape(data.getOrderNumber().isEmpty() ? "-" : data.getOrderNumber());
        StringBuilder zpl = new StringBuilder();
        zpl.append("^XA\n");
        zpl.append("^CI28\n");
        zpl.append("^PW").append(pw).append('\n');
        zpl.append("^LL").append(ll).append('\n');
        zpl.append("^LH0,0\n");

        int accent = LabelLayout.mmToDots(2.4f, dpi);
        zpl.append("^FO0,0^GB").append(pw).append(',').append(accent).append(',').append(accent).append("^FS\n");

        int headerY = accent + 8;
        int logoW = LabelLayout.mmToDots(32f, dpi);
        int logoH = LabelLayout.mmToDots(16f, dpi);
        int textX = m;
        BufferedImage logo = LabelAssets.loadLogo();
        if (logo != null) {
            zpl.append("^FO").append(m).append(',').append(headerY)
                    .append(ZplGraphic.toGfa(logo, logoW, logoH))
                    .append("^FS\n");
            textX = m + logoW + 14;
        } else {
            zpl.append("^FO").append(m).append(',').append(headerY + 8)
                    .append("^A0N,44,44^FDeship^FS\n");
            textX = m + LabelLayout.mmToDots(22f, dpi);
        }

        int orderFont = LabelLayout.orderFontDots(order);
        zpl.append("^FO").append(textX).append(',').append(headerY)
                .append("^A0N,26,26^FDPEDIDO^FS\n");
        zpl.append("^FO").append(textX).append(',').append(headerY + 28)
                .append("^A0N,").append(orderFont).append(',').append(orderFont)
                .append("^FD").append(order).append("^FS\n");

        String volume = String.valueOf(data.getVolumeIndex());
        int volBoxW = LabelLayout.mmToDots(26f, dpi);
        int volBoxH = Math.max(logoH, 28 + orderFont);
        int volX = pw - m - volBoxW;
        zpl.append("^FO").append(volX).append(',').append(headerY)
                .append("^GB").append(volBoxW).append(',').append(volBoxH).append(",").append(volBoxH).append("^FS\n");
        zpl.append("^FO").append(volX + 8).append(',').append(headerY + 8)
                .append("^FR^A0N,22,22^FDVOL^FS\n");
        zpl.append("^FO").append(volX + 8).append(',').append(headerY + 34)
                .append("^FR^A0N,48,48^FD").append(volume).append("^FS\n");

        int barY = headerY + Math.max(logoH, volBoxH) + 10;
        zpl.append("^FO").append(m).append(',').append(barY)
                .append("^GB").append(pw - 2 * m).append(",8,8^FS\n");

        int contentY = barY + 18;
        int captionH = 28;
        int availH = ll - contentY - m - captionH;
        int leftMin = LabelLayout.mmToDots(40f, dpi);
        int availW = Math.max(LabelLayout.mmToDots(32f, dpi), pw - m - leftMin - 12);
        int mag = 10;
        int qrDots = 43 * mag;
        while (mag > 6 && (qrDots > availH || qrDots > availW)) {
            mag--;
            qrDots = 43 * mag;
        }

        int qrX = pw - m - qrDots;
        int qrY = contentY;
        int areaX = qrX - 6;
        int areaY = qrY - 6;
        int areaSize = qrDots + 12;
        boolean[][] qrModules = QrMatrix.encode(data.getQrPayload());
        int moduleCount = qrModules.length;
        int quiet = 3;
        int moduleDots = Math.max(2, areaSize / (moduleCount + quiet * 2));
        BufferedImage qrImage = QrMatrix.toImage(qrModules, moduleDots, quiet);
        int qrPrinted = qrImage.getWidth();
        int qrPrintX = areaX + Math.max(0, (areaSize - qrPrinted) / 2);
        int qrPrintY = areaY + Math.max(0, (areaSize - qrPrinted) / 2);
        zpl.append("^FO").append(qrPrintX).append(',').append(qrPrintY)
                .append(ZplGraphic.toGfa(qrImage, qrPrinted, qrPrinted))
                .append("^FS\n");
        zpl.append("^FO").append(areaX).append(',').append(areaY + areaSize + 4)
                .append("^FB").append(areaSize).append(",1,0,C")
                .append("^A0N,24,24^FDESCANEIE O QR^FS\n");

        int y = contentY;
        zpl.append("^FO").append(m).append(',').append(y)
                .append("^A0N,28,28^FDPRODUTOS^FS\n");
        y += 36;

        int weightBlockH = 118;
        int maxItemY = ll - m - weightBlockH - 8;
        int printed = 0;
        for (LabelContent.Line line : data.getLines()) {
            if (y + 72 > maxItemY) {
                zpl.append("^FO").append(m).append(',').append(y)
                        .append("^A0N,30,30^FD...^FS\n");
                break;
            }
            zpl.append("^FO").append(m).append(',').append(y)
                    .append("^A0N,40,40^FD")
                    .append(LabelLayout.zplEscape(line.codigo))
                    .append("^FS\n");
            y += 44;
            String detail = "x" + line.quantidade
                    + "   " + LabelLayout.formatWeight(line.pesoLinhaKg);
            zpl.append("^FO").append(m).append(',').append(y)
                    .append("^A0N,32,32^FD").append(detail).append("^FS\n");
            y += 40;
            printed++;
        }
        if (printed == 0) {
            zpl.append("^FO").append(m).append(',').append(y)
                    .append("^A0N,30,30^FD-^FS\n");
        }

        int weightY = ll - m - weightBlockH;
        int leftW = Math.max(80, qrX - m - 16);
        zpl.append("^FO").append(m).append(',').append(weightY)
                .append("^GB").append(leftW).append(",6,6^FS\n");
        zpl.append("^FO").append(m).append(',').append(weightY + 14)
                .append("^A0N,28,28^FDPESO CONFERIDO^FS\n");
        zpl.append("^FO").append(m).append(',').append(weightY + 48)
                .append("^A0N,64,64^FD")
                .append(LabelLayout.zplEscape(LabelLayout.formatWeight(data.getMeasuredWeightKg())))
                .append("^FS\n");

        zpl.append("^XZ\n");
        return zpl.toString();
    }
}
