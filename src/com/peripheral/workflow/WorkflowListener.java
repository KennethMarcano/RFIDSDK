package com.peripheral.workflow;

import com.peripheral.core.PeripheralDataEvent;

public interface WorkflowListener {

    void onWeightUpdate(PeripheralDataEvent event);

    void onTagRead(PeripheralDataEvent event);

    /** Inventário RFID do pedido atualizado (códigos únicos detectados). */
    default void onTagInventoryUpdated(java.util.List<String> detectedCodes, int expectedCount) {
    }

    void onStepChanged(WorkflowStep step, String message);

    /** Aguardando o operador iniciar a leitura de tags RFID. */
    default void onAwaitingTagReadingStart() {
    }

    /** Aguardando o operador iniciar a pesagem (tags já lidas ou RFID desligado). */
    void onAwaitingWeighingStart();

    /** Fase de leitura RFID ativa — peso ainda não armado. */
    default void onTagReadingInProgress() {
    }

    void onStabilizationProgress(String message);

    void onCycleCompleted(WorkflowContext context);

    void onReadingRecorded(WorkflowReadingRecord record);

    void onSessionCleared();

    void onWaitingForNext();

    void onError(String message, Throwable cause);

    void onStopped();

    default void onOrderLoaded(com.peripheral.pedido.Pedido pedido) {
    }

    default void onVolumeChanged(int currentIndex, int totalVolumes) {
    }

    default void onValidationResult(PedidoValidationService.ValidationResult result) {
    }

    default void onOperatorReviewRequired(String message, com.peripheral.workflow.WorkflowContext context) {
    }

    default void onCameraServiceStatus(boolean available, String detail) {
    }

    default void onOrderCompleted(com.peripheral.pedido.Pedido pedido) {
    }
}
