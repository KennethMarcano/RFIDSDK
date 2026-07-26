package com.peripheral.workflow;

import com.peripheral.core.PeripheralException;

public interface WorkflowController {

    void start(WorkflowConfig config, WorkflowListener listener) throws PeripheralException;

    void stop();

    void restartSession() throws PeripheralException;

    /** Inicia a fase de leitura RFID (antes da pesagem). */
    default void confirmTagReadingStart() {
    }

    /** Inicia a fase de pesagem (RFID deve estar parado). */
    void confirmWeighingStart();

    void acknowledgeNext();

    boolean isRunning();

    boolean isWaitingForNext();

    boolean isSimulationMode();

    void simulateWeighing(WorkflowMockScenario scenario);

    WorkflowSessionStore getSessionStore();

    default boolean isOperatorReview() {
        return false;
    }

    default void operatorConfirmVolume() throws PeripheralException {
    }

    default void operatorRereadRfid() throws PeripheralException {
    }

    default void operatorCapturePhoto() throws PeripheralException {
    }

    default void operatorReanalyze() throws PeripheralException {
    }
}
