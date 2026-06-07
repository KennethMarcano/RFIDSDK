package com.peripheral.workflow;

import java.util.EnumSet;
import java.util.Set;

public class WorkflowConfig {

    public static final int DEFAULT_RFID_READ_MS = 1000;
    public static final int DEFAULT_STABILIZATION_MS = 1500;
    public static final int FAST_SIMULATION_STABILIZATION_MS = 200;
    public static final double MIN_WEIGHT_KG = 0.001;

    private final Set<WorkflowStep> enabledSteps;
    private final int rfidReadDurationMs;
    private final boolean simulationMode;

    public WorkflowConfig(Set<WorkflowStep> enabledSteps, int rfidReadDurationMs) {
        this(enabledSteps, rfidReadDurationMs, false);
    }

    public WorkflowConfig(Set<WorkflowStep> enabledSteps, int rfidReadDurationMs, boolean simulationMode) {
        EnumSet<WorkflowStep> steps = EnumSet.of(WorkflowStep.WEIGHING);
        if (enabledSteps != null) {
            steps.addAll(enabledSteps);
        }
        this.enabledSteps = EnumSet.copyOf(steps);
        this.rfidReadDurationMs = rfidReadDurationMs > 0 ? rfidReadDurationMs : DEFAULT_RFID_READ_MS;
        this.simulationMode = simulationMode;
    }

    public boolean isEnabled(WorkflowStep step) {
        return enabledSteps.contains(step);
    }

    public int getRfidReadDurationMs() {
        return rfidReadDurationMs;
    }

    public int getStabilizationMs() {
        return DEFAULT_STABILIZATION_MS;
    }

    public Set<WorkflowStep> getEnabledSteps() {
        return EnumSet.copyOf(enabledSteps);
    }

    public boolean isSimulationMode() {
        return simulationMode;
    }
}
