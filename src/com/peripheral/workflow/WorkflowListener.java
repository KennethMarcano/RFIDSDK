package com.peripheral.workflow;

import com.peripheral.core.PeripheralDataEvent;

public interface WorkflowListener {

    void onWeightUpdate(PeripheralDataEvent event);

    /**
     * Leitura da balança com tara lógica aplicada.
     * grossKg = peso bruto; netKg = líquido (bruto − tara); tareKg = offset ativo.
     */
    default void onScaleReading(double grossKg, double netKg, double tareKg,
                                boolean tareActive, boolean stable) {
        onWeightUpdate(PeripheralDataEvent.builder(null)
                .weight(String.format(java.util.Locale.US, "%.3f", netKg))
                .stable(stable)
                .build());
    }

    default void onTareChanged(double tareKg, boolean active) {
    }

    void onTagRead(PeripheralDataEvent event);

    /** Inventário RFID do pedido atualizado (códigos únicos detectados). */
    default void onTagInventoryUpdated(java.util.List<String> detectedCodes, int expectedCount) {
    }

    void onStepChanged(WorkflowStep step, String message);

    void onAwaitingWeighingStart();

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
