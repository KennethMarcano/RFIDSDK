package com.peripheral.workflow;

import com.peripheral.core.PeripheralException;

public interface WorkflowController {

    void start(WorkflowConfig config, WorkflowListener listener) throws PeripheralException;

    void stop();

    void restartSession() throws PeripheralException;

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

    /** Captura tara lógica a partir do peso bruto estável atual (caixa opcional). */
    default void applyTare() throws PeripheralException {
    }

    /**
     * Tara lógica com peso bruto informado (útil na simulação, onde não há leitura contínua).
     */
    default void applyTare(double grossWeightKg) throws PeripheralException {
        applyTare();
    }

    /** Remove a tara lógica; o peso líquido volta a ser igual ao bruto. */
    default void clearTare() {
    }

    default boolean isTareActive() {
        return false;
    }

    default double getTareKg() {
        return 0;
    }
}
