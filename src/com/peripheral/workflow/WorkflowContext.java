package com.peripheral.workflow;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public class WorkflowContext {

    private double weightKg;
    private boolean weightStable;
    private long cycleStartedMs;
    private String photoPath;
    private final Set<String> tagCodes = new LinkedHashSet<>();
    private final Set<String> tagEpcs = new LinkedHashSet<>();

    public void beginCycle(double weightKg, boolean weightStable) {
        this.weightKg = weightKg;
        this.weightStable = weightStable;
        this.cycleStartedMs = System.currentTimeMillis();
        this.photoPath = null;
        tagCodes.clear();
        tagEpcs.clear();
    }

    public double getWeightKg() {
        return weightKg;
    }

    public boolean isWeightStable() {
        return weightStable;
    }

    public long getCycleStartedMs() {
        return cycleStartedMs;
    }

    public String getPhotoPath() {
        return photoPath;
    }

    public void setPhotoPath(String photoPath) {
        this.photoPath = photoPath;
    }

    public void addTag(String epc, String code) {
        if (code != null && !code.trim().isEmpty()) {
            tagCodes.add(code.trim());
        }
        if (epc != null && !epc.trim().isEmpty()) {
            tagEpcs.add(epc.trim());
        }
    }

    public Set<String> getTagCodes() {
        return Collections.unmodifiableSet(tagCodes);
    }

    public Set<String> getTagEpcs() {
        return Collections.unmodifiableSet(tagEpcs);
    }

    public void updateWeight(double weightKg, boolean weightStable) {
        this.weightKg = weightKg;
        this.weightStable = weightStable;
    }
}
