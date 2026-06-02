package com.peripheral.workflow;

import java.util.EnumSet;
import java.util.Set;

public class WorkflowConfig {

    public static final int DEFAULT_RFID_READ_MS = 1000;
    public static final double MIN_WEIGHT_KG = 0.001;

    private final Set<WorkflowStep> enabledSteps;
    private final int rfidReadDurationMs;

    public WorkflowConfig(Set<WorkflowStep> enabledSteps, int rfidReadDurationMs) {
        EnumSet<WorkflowStep> steps = EnumSet.of(WorkflowStep.WEIGHING);
        if (enabledSteps != null) {
            steps.addAll(enabledSteps);
        }
        this.enabledSteps = EnumSet.copyOf(steps);
        this.rfidReadDurationMs = rfidReadDurationMs > 0 ? rfidReadDurationMs : DEFAULT_RFID_READ_MS;
    }

    public boolean isEnabled(WorkflowStep step) {
        return enabledSteps.contains(step);
    }

    public int getRfidReadDurationMs() {
        return rfidReadDurationMs;
    }

    public Set<WorkflowStep> getEnabledSteps() {
        return EnumSet.copyOf(enabledSteps);
    }
}
