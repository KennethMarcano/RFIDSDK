package com.peripheral.workflow;

import com.peripheral.core.PeripheralException;
import com.peripheral.core.ReadablePeripheral;
import com.peripheral.printer.LabelPrinter;
import com.peripheral.session.PeripheralSessionManager;
import com.peripheral.session.PeripheralSlot;
import com.peripheral.workflow.label.LabelContent;
import com.peripheral.workflow.label.LabelLayout;
import com.peripheral.workflow.label.PdfLabelGenerator;
import com.peripheral.workflow.label.ZplLabelGenerator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class LabelPrintService {

    private final PdfLabelGenerator pdfLabelGenerator = new PdfLabelGenerator();
    private final ZplLabelGenerator zplLabelGenerator = new ZplLabelGenerator();
    private final PeripheralSessionManager sessionManager;

    public LabelPrintService() {
        this(null);
    }

    public LabelPrintService(PeripheralSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    public void generateAndPrint(WorkflowContext context, Path sessionDirectory, int labelIndex)
            throws IOException, PeripheralException {
        LabelContent content = LabelContent.from(context);
        float widthMm = LabelLayout.DEFAULT_WIDTH_MM;
        float heightMm = LabelLayout.DEFAULT_HEIGHT_MM;
        LabelPrinter printer = findPrinter();
        if (printer != null) {
            widthMm = printer.getLabelWidthMm();
            heightMm = printer.getLabelHeightMm();
        }

        Path pdfPath = pdfLabelGenerator.generate(content, widthMm, heightMm, sessionDirectory, labelIndex);
        context.setLabelPdfPath(pdfPath.toString());

        Path zplPath = zplLabelGenerator.generate(content, widthMm, heightMm, sessionDirectory, labelIndex);
        context.setLabelZplPath(zplPath.toString());

        if (printer == null) {
            return;
        }
        String zpl = new String(Files.readAllBytes(zplPath), StandardCharsets.US_ASCII);
        printer.printZpl(zpl);
    }

    private LabelPrinter findPrinter() {
        if (sessionManager == null) {
            return null;
        }
        ReadablePeripheral device = sessionManager.getDevice(PeripheralSlot.PRINTER);
        if (device instanceof LabelPrinter && device.isConnected()) {
            return (LabelPrinter) device;
        }
        return null;
    }
}
