package com.peripheral.workflow;

import com.peripheral.core.PeripheralDataEvent;

public interface WorkflowListener {

    void onWeightUpdate(PeripheralDataEvent event);

    void onTagRead(PeripheralDataEvent event);

    void onStepChanged(WorkflowStep step, String message);

    void onCycleCompleted(WorkflowContext context);

    void onWaitingForNext();

    void onError(String message, Throwable cause);

    void onStopped();
}
