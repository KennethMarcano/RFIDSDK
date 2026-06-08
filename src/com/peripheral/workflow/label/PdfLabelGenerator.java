package com.peripheral.workflow.label;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class PdfLabelGenerator {

    public Path generate(double weightKg, Path sessionDirectory, int labelIndex) throws IOException {
        if (sessionDirectory == null) {
            throw new IOException("Diretório da sessão não definido");
        }
        Files.createDirectories(sessionDirectory);

        float widthPt = LabelLayout.mmToPoints(LabelLayout.LABEL_WIDTH_MM);
        float heightPt = LabelLayout.mmToPoints(LabelLayout.LABEL_HEIGHT_MM);
        String weightText = LabelLayout.formatWeight(weightKg);

        Path outputFile = sessionDirectory.resolve(String.format("label_%03d.pdf", Math.max(1, labelIndex)));

        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(widthPt, heightPt));
            document.addPage(page);

            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                float fontSize = LabelLayout.PDF_WEIGHT_FONT_SIZE;
                float textWidth = PDType1Font.HELVETICA_BOLD.getStringWidth(weightText) / 1000f * fontSize;
                float x = Math.max(12f, (widthPt - textWidth) / 2f);
                float y = heightPt / 2f - fontSize / 3f;

                content.beginText();
                content.setFont(PDType1Font.HELVETICA_BOLD, fontSize);
                content.newLineAtOffset(x, y);
                content.showText(weightText);
                content.endText();
            }

            document.save(outputFile.toFile());
        }

        return outputFile.toAbsolutePath();
    }
}
