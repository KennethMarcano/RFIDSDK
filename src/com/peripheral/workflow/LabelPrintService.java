package com.peripheral.workflow;

import com.peripheral.workflow.label.LabelLayout;
import com.peripheral.workflow.label.PdfLabelGenerator;
import com.peripheral.workflow.label.PdfToZplConverter;
import com.peripheral.workflow.label.ZplPrintTransport;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class LabelPrintService {

    private final PdfLabelGenerator pdfLabelGenerator = new PdfLabelGenerator();
    private final PdfToZplConverter pdfToZplConverter = new PdfToZplConverter();
    private final ZplPrintTransport zplPrintTransport = new ZplPrintTransport();

    public void generateLabelPdf(WorkflowContext context, Path sessionDirectory, int labelIndex) throws IOException {
        Path pdfPath = pdfLabelGenerator.generate(context.getWeightKg(), sessionDirectory, labelIndex);
        context.setLabelPdfPath(pdfPath.toString());
    }

    public void printLabel(WorkflowContext context, Path sessionDirectory, int labelIndex) throws IOException {
        String pdfPathValue = context.getLabelPdfPath();
        if (pdfPathValue == null || pdfPathValue.trim().isEmpty()) {
            throw new IOException("PDF da etiqueta não foi gerado");
        }

        Path pdfPath = Paths.get(pdfPathValue);
        String zpl = pdfToZplConverter.convert(pdfPath, LabelLayout.ZPL_DPI);
        Path zplPath = zplPrintTransport.send(zpl, sessionDirectory, labelIndex);
        context.setLabelZplPath(zplPath.toString());
    }
}
