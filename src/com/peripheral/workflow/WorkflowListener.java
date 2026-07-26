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

    /**
     * Resultado da análise de IA (fallback) pronto — para exibir pop-up ao operador.
     *
     * @param identified true se todos os produtos esperados foram identificados
     * @param message    mensagem retornada pela IA
     */
    default void onAiAnalysisResult(boolean identified, String message,
                                    com.peripheral.workflow.WorkflowContext context) {
    }

    default void onCameraServiceStatus(boolean available, String detail) {
    }

    default void onOrderCompleted(com.peripheral.pedido.Pedido pedido) {
    }

    /** Fila: pedido atual (1-based) e total. */
    default void onOrderQueueUpdated(int currentIndex, int totalOrders) {
    }

    /**
     * Um pedido terminou e o próximo já vai começar do zero.
     */
    default void onNextPedidoStarted(com.peripheral.pedido.Pedido completed,
                                     com.peripheral.pedido.Pedido next,
                                     int nextIndex, int total) {
    }

    /** Todos os pedidos da fila foram concluídos. */
    default void onAllOrdersCompleted() {
    }

    /**
     * Tara em medição ou concluída.
     *
     * @param tareKg    valor atual da tara (0 enquanto mede)
     * @param measuring true enquanto o RFID está desligado aguardando o peso estabilizar
     */
    default void onTareChanged(double tareKg, boolean measuring, String message) {
    }
}
