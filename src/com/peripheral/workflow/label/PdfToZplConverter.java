package com.peripheral.workflow.label;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

public class PdfToZplConverter {

    public String convert(Path pdfPath, int dpi) throws IOException {
        if (pdfPath == null || !pdfPath.toFile().isFile()) {
            throw new IOException("PDF da etiqueta não encontrado: " + pdfPath);
        }

        try (PDDocument document = PDDocument.load(pdfPath.toFile())) {
            if (document.getNumberOfPages() == 0) {
                throw new IOException("PDF da etiqueta sem páginas");
            }
            PDFRenderer renderer = new PDFRenderer(document);
            BufferedImage image = renderer.renderImageWithDPI(0, dpi, ImageType.BINARY);
            return toZpl(image, dpi);
        }
    }

    private String toZpl(BufferedImage image, int dpi) {
        int width = image.getWidth();
        int height = image.getHeight();
        int rowBytes = (width + 7) / 8;
        int totalBytes = rowBytes * height;

        StringBuilder hex = new StringBuilder(totalBytes * 2);
        for (int y = 0; y < height; y++) {
            for (int xByte = 0; xByte < rowBytes; xByte++) {
                int value = 0;
                for (int bit = 0; bit < 8; bit++) {
                    int x = xByte * 8 + bit;
                    if (x < width && isBlack(image.getRGB(x, y))) {
                        value |= 1 << (7 - bit);
                    }
                }
                hex.append(String.format("%02X", value));
            }
        }

        int labelWidthDots = LabelLayout.mmToDots(LabelLayout.DEFAULT_WIDTH_MM, dpi);
        int labelHeightDots = LabelLayout.mmToDots(LabelLayout.DEFAULT_HEIGHT_MM, dpi);

        return "^XA"
                + "^PW" + labelWidthDots
                + "^LL" + labelHeightDots
                + "^FO0,0"
                + "^GFA," + totalBytes + "," + totalBytes + "," + rowBytes + "," + hex
                + "^FS"
                + "^XZ";
    }

    private boolean isBlack(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return r < 128 && g < 128 && b < 128;
    }
}
