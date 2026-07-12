package com.peripheral.workflow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class WorkflowReadingRecord {

    private final int index;
    private final long timestampMs;
    private final double weightKg;
    private final List<String> tagCodes;
    private final String photoPath;
    private final String labelPdfPath;
    private final String numeroPedido;
    private final int volumeIndex;
    private final String validationStatus;
    private final String aiMessage;
    private final boolean operatorConfirmed;

    public WorkflowReadingRecord(int index, long timestampMs, double weightKg,
                                 List<String> tagCodes, String photoPath, String labelPdfPath) {
        this(index, timestampMs, weightKg, tagCodes, photoPath, labelPdfPath,
                null, 0, null, null, false);
    }

    public WorkflowReadingRecord(int index, long timestampMs, double weightKg,
                                 List<String> tagCodes, String photoPath, String labelPdfPath,
                                 String numeroPedido, int volumeIndex, String validationStatus,
                                 String aiMessage, boolean operatorConfirmed) {
        this.index = index;
        this.timestampMs = timestampMs;
        this.weightKg = weightKg;
        this.tagCodes = tagCodes != null
                ? Collections.unmodifiableList(new ArrayList<>(tagCodes))
                : Collections.emptyList();
        this.photoPath = photoPath;
        this.labelPdfPath = labelPdfPath;
        this.numeroPedido = numeroPedido;
        this.volumeIndex = volumeIndex;
        this.validationStatus = validationStatus;
        this.aiMessage = aiMessage;
        this.operatorConfirmed = operatorConfirmed;
    }

    public int getIndex() {
        return index;
    }

    public long getTimestampMs() {
        return timestampMs;
    }

    public double getWeightKg() {
        return weightKg;
    }

    public List<String> getTagCodes() {
        return tagCodes;
    }

    public String getPhotoPath() {
        return photoPath;
    }

    public String getLabelPdfPath() {
        return labelPdfPath;
    }

    public String getNumeroPedido() {
        return numeroPedido;
    }

    public int getVolumeIndex() {
        return volumeIndex;
    }

    public String getValidationStatus() {
        return validationStatus;
    }

    public String getAiMessage() {
        return aiMessage;
    }

    public boolean isOperatorConfirmed() {
        return operatorConfirmed;
    }

    public boolean hasPhoto() {
        return photoPath != null && !photoPath.trim().isEmpty();
    }

    public boolean hasLabel() {
        return labelPdfPath != null && !labelPdfPath.trim().isEmpty();
    }
}
