package com.peripheral.workflow.label;

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
        int margin = LabelLayout.mmToDots(4f, dpi);
        int y = margin + 10;

        StringBuilder zpl = new StringBuilder();
        zpl.append("^XA\n");
        zpl.append("^CI28\n");
        zpl.append("^PW").append(pw).append('\n');
        zpl.append("^LL").append(ll).append('\n');
        zpl.append("^LH0,0\n");

        String order = LabelLayout.zplEscape(data.getOrderNumber().isEmpty() ? "-" : data.getOrderNumber());
        zpl.append("^FO").append(margin).append(',').append(y)
                .append("^A0N,40,40^FDPedido ").append(order).append("^FS\n");
        y += 48;
        zpl.append("^FO").append(margin).append(',').append(y)
                .append("^A0N,24,24^FDVolume ").append(data.getVolumeIndex()).append("^FS\n");
        y += 36;

        for (LabelContent.Line line : data.getLines()) {
            String row = LabelLayout.zplEscape(line.codigo)
                    + "  x" + line.quantidade
                    + "  " + LabelLayout.formatWeight(line.pesoLinhaKg);
            zpl.append("^FO").append(margin).append(',').append(y)
                    .append("^A0N,22,22^FD").append(row).append("^FS\n");
            y += 28;
        }

        y += 8;
        zpl.append("^FO").append(margin).append(',').append(y)
                .append("^A0N,26,26^FDPeso conferido: ")
                .append(LabelLayout.zplEscape(LabelLayout.formatWeight(data.getMeasuredWeightKg())))
                .append("^FS\n");

        String qr = LabelLayout.zplEscape(data.getQrPayload().replace('\n', ';'));
        int mag = 4;
        int qrDots = 29 * mag + 20;
        int qrX = Math.max(0, (pw - qrDots) / 2);
        int qrY = Math.max(y + 20, ll - qrDots - margin);
        zpl.append("^FO").append(qrX).append(',').append(qrY)
                .append("^BQN,2,").append(mag).append('\n');
        zpl.append("^FDQA,").append(qr).append("^FS\n");
        zpl.append("^XZ\n");
        return zpl.toString();
    }
}
