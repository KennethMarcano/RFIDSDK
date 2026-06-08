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

    public WorkflowReadingRecord(int index, long timestampMs, double weightKg,
                                 List<String> tagCodes, String photoPath, String labelPdfPath) {
        this.index = index;
        this.timestampMs = timestampMs;
        this.weightKg = weightKg;
        this.tagCodes = tagCodes != null
                ? Collections.unmodifiableList(new ArrayList<>(tagCodes))
                : Collections.emptyList();
        this.photoPath = photoPath;
        this.labelPdfPath = labelPdfPath;
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

    public boolean hasPhoto() {
        return photoPath != null && !photoPath.trim().isEmpty();
    }

    public boolean hasLabel() {
        return labelPdfPath != null && !labelPdfPath.trim().isEmpty();
    }
}
