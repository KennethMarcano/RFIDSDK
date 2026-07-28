package com.peripheral.workflow;

import com.peripheral.camera.CameraHardware;
import com.peripheral.camera.CameraMicroserviceClient;
import com.peripheral.camera.CameraServiceException;
import com.peripheral.core.DeviceModelEntry;
import com.peripheral.core.PeripheralDataEvent;
import com.peripheral.core.PeripheralDataListener;
import com.peripheral.core.PeripheralException;
import com.peripheral.core.PeripheralSafeIo;
import com.peripheral.core.ReadablePeripheral;
import com.peripheral.core.RfidConfigurable;
import com.peripheral.core.SerialConnectionConfig;
import com.peripheral.pedido.Pedido;
import com.peripheral.pedido.PedidoItem;
import com.peripheral.pedido.PedidoVolume;
import com.peripheral.scale.DigitronDgnParser;
import com.peripheral.scale.ScaleWeightFormat;
import com.peripheral.session.PeripheralConnectionHandle;
import com.peripheral.session.PeripheralSessionManager;
import com.peripheral.session.PeripheralSlot;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class OrderAwareWorkflowOrchestrator implements WorkflowController {

    /** Janela de estabilidade exigida para aceitar a tara. */
    private static final long TARE_STABLE_WINDOW_MS = 1200;
    private static final long TARE_TIMEOUT_MS = 15_000;
    private static final double TARE_STABLE_TOLERANCE_KG = 0.005;
    /** Tempo de exibição do pop-up de sucesso (sem botão). */
    private static final long OUTCOME_MESSAGE_MS = 2000;
    /** Tempo de exibição do pop-up de divergência (sem botão). */
    private static final long DIVERGENCE_OUTCOME_MESSAGE_MS = 10_000;
    /** Delay após sucesso antes de iniciar o próximo pedido (retirar produtos). */
    private static final long NEXT_PEDIDO_DELAY_MS = 7000;

    private final PeripheralSessionManager sessionManager;
    private final List<Pedido> pedidos;
    private Pedido pedido;
    private int pedidoIndex;
    private final CameraMicroserviceClient cameraClient;
    private final PedidoValidationService validationService = new PedidoValidationService();
    private PhotoCaptureService photoCaptureService;
    private LabelPrintService labelPrintService;
    private final WorkflowSessionStore sessionStore = new WorkflowSessionStore();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "order-workflow");
        t.setDaemon(true);
        return t;
    });

    private WorkflowConfig config;
    private WorkflowListener listener;
    private final WorkflowContext context = new WorkflowContext();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean cycleInProgress = new AtomicBoolean(false);
    private final AtomicBoolean armed = new AtomicBoolean(false);
    private final AtomicBoolean waitingForNext = new AtomicBoolean(false);
    private final AtomicBoolean awaitingTagStart = new AtomicBoolean(false);
    private final AtomicBoolean awaitingWeightStart = new AtomicBoolean(false);
    private final AtomicBoolean operatorReview = new AtomicBoolean(false);
    private final AtomicLong stableSinceMs = new AtomicLong(0);
    private final AtomicBoolean stabilizationTriggered = new AtomicBoolean(false);

    private int currentVolumeIndex;
    private double lastStableWeight;
    private final AtomicBoolean rfidCollecting = new AtomicBoolean(false);
    private final AtomicLong lastRfidSignalMs = new AtomicLong(0);
    private final AtomicInteger rfidTransitionToken = new AtomicInteger();
    /** Tara lógica (caixa). Reinicia entre pedidos/sessões. */
    private volatile double tareKg;
    private volatile double lastGrossKg;
    private volatile boolean lastGrossStable;
    /** Enquanto true o RF fica desligado e nenhum ciclo dispara (medindo a caixa). */
    private final AtomicBoolean taring = new AtomicBoolean(false);
    /** Invalida uma medição de tara em andamento quando a sessão muda. */
    private final AtomicInteger tareToken = new AtomicInteger();
    /** Revalidação de finalizar: RFID desligado aguardando peso limpo. */
    private final AtomicBoolean confirmingWeight = new AtomicBoolean(false);
    private final AtomicInteger confirmToken = new AtomicInteger();

    public OrderAwareWorkflowOrchestrator(PeripheralSessionManager sessionManager,
                                          Pedido pedido,
                                          CameraMicroserviceClient cameraClient) {
        this(sessionManager,
                pedido != null ? Collections.singletonList(pedido) : Collections.emptyList(),
                cameraClient);
    }

    public OrderAwareWorkflowOrchestrator(PeripheralSessionManager sessionManager,
                                          List<Pedido> pedidos,
                                          CameraMicroserviceClient cameraClient) {
        this.sessionManager = sessionManager;
        this.pedidos = pedidos != null ? new ArrayList<>(pedidos) : new ArrayList<>();
        this.pedidoIndex = 0;
        this.pedido = this.pedidos.isEmpty() ? null : this.pedidos.get(0);
        this.cameraClient = cameraClient;
    }

    @Override
    public void start(WorkflowConfig config, WorkflowListener listener) throws PeripheralException {
        if (pedidos.isEmpty() || pedido == null || pedido.getVolumeCount() == 0) {
            throw new PeripheralException("Carregue um pedido válido antes de iniciar.");
        }
        if (!config.isSimulationMode()) {
            if (!sessionManager.isConnected(PeripheralSlot.SCALE)) {
                throw new PeripheralException("Conecte a balança antes de iniciar o fluxo");
            }
            if (config.isEnabled(WorkflowStep.RFID_READ)
                    && !sessionManager.isConnected(PeripheralSlot.RFID_READER)) {
                throw new PeripheralException("Conecte o leitor RFID ou desabilite a leitura RFID no fluxo");
            }
        }
        this.config = config;
        this.listener = listener;
        this.currentVolumeIndex = 0;
        running.set(true);
        armed.set(false);
        waitingForNext.set(false);
        operatorReview.set(false);
        cycleInProgress.set(false);
        rfidCollecting.set(false);
        cancelTareMeasurement();
        context.clearTags();
        resetStabilizationTracking();
        resetPhaseFlags();

        try {
            sessionStore.beginSession();
        } catch (IOException e) {
            throw new PeripheralException("Não foi possível iniciar a sessão: " + e.getMessage());
        }

        notifyCameraStatus();
        if (listener != null) {
            listener.onOrderLoaded(pedido);
            listener.onVolumeChanged(currentVolumeIndex + 1, pedido.getVolumeCount());
            if (pedidos.size() > 1) {
                listener.onOrderQueueUpdated(pedidoIndex + 1, pedidos.size());
            }
        }

        if (!config.isSimulationMode()) {
            ReadablePeripheral scale = sessionManager.getDevice(PeripheralSlot.SCALE);
            // Garante que o listener do fluxo substitui o da tela de configuração.
            PeripheralSafeIo.stopReading(scale);
            scale.startContinuousReading(scaleListener);
            // RFID só liga na fase de tags — evita interferência no peso.
        }
        notifyPhaseStart();
    }

    @Override
    public void stop() {
        running.set(false);
        rfidTransitionToken.incrementAndGet();
        cycleInProgress.set(false);
        armed.set(false);
        waitingForNext.set(false);
        awaitingTagStart.set(false);
        awaitingWeightStart.set(false);
        operatorReview.set(false);
        resetStabilizationTracking();
        stopScaleReading();
        stopRfidReading();
        rfidCollecting.set(false);
        cancelTareMeasurement();
        context.clearTags();
        sessionStore.clearSession();
        if (listener != null) {
            listener.onSessionCleared();
            listener.onStopped();
        }
    }

    @Override
    public void restartSession() throws PeripheralException {
        if (!running.get()) {
            throw new PeripheralException("O fluxo não está em execução.");
        }
        if (cycleInProgress.get()) {
            throw new PeripheralException("Aguarde o ciclo atual terminar para reiniciar a sessão.");
        }
        rfidTransitionToken.incrementAndGet();
        currentVolumeIndex = 0;
        waitingForNext.set(false);
        operatorReview.set(false);
        armed.set(false);
        cycleInProgress.set(false);
        rfidCollecting.set(false);
        cancelTareMeasurement();
        // Reinicia a fila de pedidos do zero.
        pedidoIndex = 0;
        pedido = pedidos.get(0);
        context.clearTags();
        resetStabilizationTracking();
        stopRfidReading();
        sessionStore.clearSession();
        try {
            sessionStore.beginSession();
        } catch (IOException e) {
            throw new PeripheralException("Não foi possível reiniciar a sessão: " + e.getMessage());
        }
        if (listener != null) {
            listener.onSessionCleared();
            listener.onOrderLoaded(pedido);
            listener.onVolumeChanged(currentVolumeIndex + 1, pedido.getVolumeCount());
            listener.onTagInventoryUpdated(Collections.emptyList(), 0);
            if (pedidos.size() > 1) {
                listener.onOrderQueueUpdated(pedidoIndex + 1, pedidos.size());
            }
        }
        resetPhaseFlags();
        notifyPhaseStart();
    }

    @Override
    public WorkflowSessionStore getSessionStore() {
        return sessionStore;
    }

    @Override
    public void confirmTagReadingStart() {
        if (!running.get() || operatorReview.get() || !isRfidEnabled()) {
            return;
        }
        if (!awaitingTagStart.compareAndSet(true, false)) {
            return;
        }
        context.clearTags();
        rfidCollecting.set(true);
        awaitingWeightStart.set(true);
        armed.set(false);
        resetStabilizationTracking();
        PedidoVolume volume = pedido.getVolume(currentVolumeIndex);
        context.setNumeroPedido(pedido.getNumero());
        context.setVolumeIndex(volume != null ? volume.getIndice() : currentVolumeIndex + 1);
        context.setCurrentVolume(volume);
        notifyTagInventory();
        try {
            if (!config.isSimulationMode()) {
                startContinuousRfidIfEnabled();
            }
        } catch (PeripheralException e) {
            awaitingTagStart.set(true);
            awaitingWeightStart.set(false);
            rfidCollecting.set(false);
            // Não usa handleCycleFailure aqui — ele chama notifyPhaseStart de novo e
            // recursa em confirmTagReadingStart se o RFID estiver offline.
            if (listener != null) {
                listener.onError(e.getMessage(), e);
            }
            notifyStep(WorkflowStep.RFID_READ,
                    "RFID indisponível: " + e.getMessage());
            return;
        }
        notifyStep(WorkflowStep.RFID_READ,
                "Lendo tags — a pesagem inicia automaticamente quando todas as tags do pedido forem identificadas");
        if (listener != null) {
            listener.onTagReadingInProgress();
        }
        maybeAutoStartWeighingIfTagsComplete();
    }

    @Override
    public void confirmWeighingStart() {
        if (!running.get() || operatorReview.get() || taring.get()) {
            return;
        }
        if (isRfidEnabled()) {
            if (!awaitingWeightStart.get() && !rfidCollecting.get()) {
                return;
            }
            awaitingWeightStart.set(false);
        } else if (!awaitingWeightStart.compareAndSet(true, false)) {
            return;
        }

        // Para o RF antes de medir — elimina interferência na balança.
        rfidCollecting.set(false);
        stopRfidReading();
        PedidoVolume volume = pedido.getVolume(currentVolumeIndex);
        context.setNumeroPedido(pedido.getNumero());
        context.setVolumeIndex(volume != null ? volume.getIndice() : currentVolumeIndex + 1);
        context.setCurrentVolume(volume);
        armed.set(true);
        resetStabilizationTracking();
        String message = config.isSimulationMode()
                ? "Modo simulação — clique em Simular pesagem estável"
                : "Todas as tags OK — RFID desligado. Aguarde estabilização do peso...";
        notifyStep(WorkflowStep.WEIGHING, message);
    }

    @Override
    public void acknowledgeNext() {
        if (!running.get() || !waitingForNext.compareAndSet(true, false)) {
            return;
        }
        advanceVolumeOrComplete();
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public boolean isWaitingForNext() {
        return waitingForNext.get();
    }

    @Override
    public boolean isSimulationMode() {
        return config != null && config.isSimulationMode();
    }

    @Override
    public boolean isOperatorReview() {
        return operatorReview.get();
    }

    @Override
    public void simulateWeighing(WorkflowMockScenario scenario) {
        if (!running.get() || scenario == null || config == null || !config.isSimulationMode()) {
            return;
        }
        if (cycleInProgress.get() || waitingForNext.get()) {
            return;
        }
        if (operatorReview.get()) {
            executor.submit(() -> runSimulatedOperatorRetry(scenario));
            return;
        }
        // Fase de tags: injeta códigos sem pesar.
        if (rfidCollecting.get() && !armed.get()) {
            injectSimulatedTags(scenario);
            return;
        }
        if (!armed.get()) {
            return;
        }
        armed.set(false);
        executor.submit(() -> runSimulatedWeighing(scenario));
    }

    @Override
    public void operatorConfirmVolume() throws PeripheralException {
        if (!operatorReview.get()) {
            return;
        }
        if (taring.get()) {
            throw new PeripheralException("Aguarde a tara terminar antes de finalizar.");
        }
        if (!confirmingWeight.compareAndSet(false, true)) {
            return;
        }

        // Se releu as tags, o RF fica ligado e distorce a balança — desliga antes de medir.
        rfidCollecting.set(false);
        stopRfidReading();

        if (config != null && config.isSimulationMode()) {
            try {
                finishOperatorConfirmWithCurrentWeight();
            } finally {
                confirmingWeight.set(false);
            }
            return;
        }

        notifyStep(WorkflowStep.WEIGHING,
                "RFID desligado — aguardando peso estável para revalidar...");
        if (listener != null) {
            listener.onOperatorReviewRequired(
                    "RFID desligado — aguarde o peso estabilizar para revalidar.", context);
        }
        int token = confirmToken.incrementAndGet();
        executor.submit(() -> runOperatorConfirmAfterScaleSettle(token));
    }

    private void runOperatorConfirmAfterScaleSettle(int token) {
        double measured = 0;
        boolean ok = false;
        try {
            Thread.sleep(700);
            long deadline = System.currentTimeMillis() + TARE_TIMEOUT_MS;
            long stableSince = 0;
            double candidate = 0;
            while (running.get() && operatorReview.get() && confirmingWeight.get()
                    && confirmToken.get() == token
                    && System.currentTimeMillis() < deadline) {
                double gross = Math.max(0, lastGrossKg);
                long now = System.currentTimeMillis();
                if (lastGrossStable) {
                    if (stableSince == 0 || Math.abs(gross - candidate) > TARE_STABLE_TOLERANCE_KG) {
                        candidate = gross;
                        stableSince = now;
                    } else if (now - stableSince >= TARE_STABLE_WINDOW_MS) {
                        measured = candidate;
                        ok = true;
                        break;
                    }
                } else {
                    stableSince = 0;
                }
                Thread.sleep(80);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (!running.get() || confirmToken.get() != token || !operatorReview.get()) {
            confirmingWeight.set(false);
            return;
        }

        try {
            if (!ok) {
                String reason = "Peso não estabilizou com o RFID desligado. "
                        + "Deixe a balança parada e tente novamente.";
                notifyStep(WorkflowStep.OPERATOR_REVIEW, "Não finalizado — " + reason);
                if (listener != null) {
                    listener.onOperatorReviewRequired("Não finalizado: " + reason, context);
                }
                return;
            }
            lastGrossKg = measured;
            lastGrossStable = true;
            finishOperatorConfirmWithCurrentWeight();
        } finally {
            confirmingWeight.set(false);
        }
    }

    /** Valida tags atuais + peso líquido atual; só avança se ambos conferirem. */
    private void finishOperatorConfirmWithCurrentWeight() {
        if (!operatorReview.get()) {
            return;
        }
        PedidoVolume volume = context.getCurrentVolume();
        double gross = lastGrossKg > 0 ? lastGrossKg : context.getWeightKg();
        double netKg = toNetKg(gross);
        context.updateWeight(netKg, true);
        PedidoValidationService.ValidationResult validation = validationService.validate(
                volume,
                context.snapshotTagCodes(),
                netKg,
                config.getWeightTolerancePercent(),
                config.getWeightToleranceKg());
        context.setValidationResult(validation);
        if (listener != null && !validation.isValid()) {
            listener.onValidationResult(validation);
        }

        if (validation.isValid()) {
            context.setOperatorConfirmed(true);
            operatorReview.set(false);
            waitingForNext.set(false);
            cycleInProgress.set(false);
            rfidCollecting.set(false);
            stopRfidReading();
            // Descarta dados da divergência: foto/IA antigos não vão para o histórico.
            context.setAiMessage(null);
            context.setMissingProducts(null);
            context.setUnexpectedProducts(null);
            context.setPhotoPath(null);
            // Foto e etiqueta só agora, com a conferência aprovada e o peso correto.
            try {
                if (config.isEnabled(WorkflowStep.PRINT_LABEL)) {
                    printLabel();
                }
            } catch (Exception e) {
                notifyStep(WorkflowStep.PRINT_LABEL,
                        "Etiqueta não gerada: "
                                + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            }
            if (config.isEnabled(WorkflowStep.CAPTURE_PHOTO)) {
                capturePhotoOptional();
            }
            if (listener != null) {
                listener.onValidationResult(validation);
            }
            recordAndAdvance("APROVADO_OPERADOR");
            return;
        }

        // Continua divergente: RFID permanece desligado; tags não são apagadas.
        String reason = validation.getSummaryMessage();
        notifyStep(WorkflowStep.OPERATOR_REVIEW,
                "Ainda divergente — " + reason
                        + ". Corrija tags/peso e valide de novo.");
        if (listener != null) {
            listener.onOperatorReviewRequired(
                    "Não finalizado: " + reason, context);
        }
    }

    /** Limpa as tags acumuladas e mantém a leitura RFID ativa se já estiver coletando. */
    @Override
    public void clearReadTags() throws PeripheralException {
        if (!running.get()) {
            return;
        }
        context.clearTags();
        notifyTagInventory();
        if (operatorReview.get()) {
            // Em revisão: liga RFID para nova coleta a partir do zero.
            rfidCollecting.set(true);
            if (config != null && !config.isSimulationMode()) {
                startContinuousRfidIfEnabled();
            }
            notifyStep(WorkflowStep.RFID_READ,
                    "Tags reiniciadas — aproxime os produtos para ler de novo.");
            return;
        }
        if (rfidCollecting.get()) {
            notifyStep(WorkflowStep.RFID_READ,
                    "Tags reiniciadas — continue aproximando os produtos.");
        } else {
            notifyStep(WorkflowStep.RFID_READ,
                    "Tags reiniciadas.");
        }
    }

    @Override
    public double getTareKg() {
        return tareKg;
    }

    /**
     * Desliga o RF, espera a balança estabilizar só com a caixa, grava a tara
     * e devolve o fluxo exatamente para a fase em que estava.
     */
    @Override
    public void captureTare() throws PeripheralException {
        if (!running.get()) {
            throw new PeripheralException("Inicie o fluxo antes de definir a tara.");
        }
        if (config != null && config.isSimulationMode()) {
            throw new PeripheralException("Tara indisponível no modo simulação.");
        }
        if (cycleInProgress.get()) {
            throw new PeripheralException("Aguarde o ciclo atual terminar para definir a tara.");
        }
        if (!taring.compareAndSet(false, true)) {
            return;
        }
        boolean wasCollectingTags = rfidCollecting.getAndSet(false);
        boolean wasArmed = armed.getAndSet(false);
        // RF ligado distorce a célula de carga — desliga antes de medir a caixa.
        stopRfidReading();
        resetStabilizationTracking();
        tareKg = 0;
        if (listener != null) {
            listener.onTareChanged(0, true,
                    "Medindo tara — RFID desligado. Deixe só a caixa na balança...");
        }
        notifyStep(WorkflowStep.WEIGHING,
                "Medindo tara — RFID desligado. Deixe só a caixa na balança...");
        int token = tareToken.incrementAndGet();
        executor.submit(() -> runTareCapture(token, wasCollectingTags, wasArmed));
    }

    private void runTareCapture(int token, boolean wasCollectingTags, boolean wasArmed) {
        double measured = 0;
        boolean ok = false;
        try {
            // Pausa para o pipeline da balança acomodar após desligar o RF.
            Thread.sleep(700);
            long deadline = System.currentTimeMillis() + TARE_TIMEOUT_MS;
            long stableSince = 0;
            double candidate = 0;
            while (running.get() && taring.get() && tareToken.get() == token
                    && System.currentTimeMillis() < deadline) {
                double gross = Math.max(0, lastGrossKg);
                long now = System.currentTimeMillis();
                if (lastGrossStable) {
                    if (stableSince == 0 || Math.abs(gross - candidate) > TARE_STABLE_TOLERANCE_KG) {
                        candidate = gross;
                        stableSince = now;
                    } else if (now - stableSince >= TARE_STABLE_WINDOW_MS) {
                        measured = candidate;
                        ok = true;
                        break;
                    }
                } else {
                    stableSince = 0;
                }
                Thread.sleep(80);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Sessão reiniciada/encerrada no meio da medição: descarta o resultado.
        if (tareToken.get() != token) {
            return;
        }
        tareKg = ok ? Math.max(0, measured) : 0;
        String message;
        if (!ok) {
            message = "Tara não estabilizou — mantida em 0. Tente novamente com a caixa parada.";
        } else if (tareKg <= 0.0005) {
            message = "Tara 0 (balança vazia) — pesagem sem caixa.";
        } else {
            message = "Tara definida: " + ScaleWeightFormat.formatGramsPlain(tareKg)
                    + " — o peso líquido ignora a caixa.";
        }
        if (listener != null) {
            listener.onTareChanged(tareKg, false, message);
        }
        taring.set(false);
        resumeAfterTare(wasCollectingTags, wasArmed, message);
    }

    /** Volta o fluxo para a fase em que estava antes da tara. */
    private void resumeAfterTare(boolean wasCollectingTags, boolean wasArmed, String tareMessage) {
        if (!running.get()) {
            return;
        }
        if (wasCollectingTags) {
            rfidCollecting.set(true);
            try {
                startContinuousRfidIfEnabled();
            } catch (PeripheralException e) {
                rfidCollecting.set(false);
                handleCycleFailure("Falha ao religar o RFID após a tara: " + e.getMessage(), e);
                return;
            }
            notifyStep(WorkflowStep.RFID_READ,
                    tareMessage + " Voltando à leitura de tags — aproxime os produtos.");
            if (listener != null) {
                listener.onTagReadingInProgress();
            }
            return;
        }
        if (wasArmed) {
            armed.set(true);
            resetStabilizationTracking();
            notifyStep(WorkflowStep.WEIGHING,
                    tareMessage + " Coloque os produtos na caixa — aguardando estabilização.");
            return;
        }
        notifyStep(WorkflowStep.WEIGHING, tareMessage);
    }

    @Override
    public void clearTare() {
        tareKg = 0;
    }

    /** Aborta uma medição de tara em andamento e zera o valor. */
    private void cancelTareMeasurement() {
        tareToken.incrementAndGet();
        taring.set(false);
        clearTare();
        confirmToken.incrementAndGet();
        confirmingWeight.set(false);
    }

    @Override
    public void operatorRereadRfid() throws PeripheralException {
        if (!operatorReview.get()) {
            return;
        }
        context.clearTags();
        rfidCollecting.set(true);
        notifyTagInventory();
        if (config.isSimulationMode()) {
            notifyStep(WorkflowStep.RFID_READ,
                    "Tags limpas — ajuste peso/códigos e clique em Simular.");
            return;
        }
        startContinuousRfidIfEnabled();
        notifyStep(WorkflowStep.RFID_READ,
                "Tags limpas — aproxime os produtos; releitura RFID ativa.");
        revalidateDuringReview();
    }

    @Override
    public void operatorCapturePhoto() throws PeripheralException {
        if (!operatorReview.get()) {
            return;
        }
        try {
            capturePhotoMandatory();
            notifyStep(WorkflowStep.CAPTURE_PHOTO, "Foto recapturada: " + context.getPhotoPath());
        } catch (IOException e) {
            throw new PeripheralException("Falha ao capturar foto: " + e.getMessage(), e);
        }
    }

    @Override
    public void operatorReanalyze() throws PeripheralException {
        if (!operatorReview.get() || !config.isAiFallbackEnabled()) {
            return;
        }
        runAiAnalysis();
    }

    private void runSimulatedOperatorRetry(WorkflowMockScenario scenario) {
        if (!operatorReview.get() || !cycleInProgress.compareAndSet(false, true)) {
            return;
        }
        try {
            notifyStep(WorkflowStep.WEIGHING, "Re-simulando peso e códigos...");
            context.clearTags();
            rfidCollecting.set(true);
            double weightKg = scenario.getWeightKg();
            context.updateWeight(weightKg, true);

            if (config.isEnabled(WorkflowStep.RFID_READ)) {
                injectSimulatedTags(scenario);
            }

            PedidoVolume volume = context.getCurrentVolume();
            PedidoValidationService.ValidationResult validation = validationService.validate(
                    volume,
                    context.snapshotTagCodes(),
                    weightKg,
                    config.getWeightTolerancePercent(),
                    config.getWeightToleranceKg());

            context.setValidationResult(validation);
            if (listener != null) {
                listener.onValidationResult(validation);
            }

            if (validation.isValid()) {
                context.setValidationStatusLabel("REVALIDADO_OK");
                notifyStep(WorkflowStep.VALIDATE_ORDER,
                        "Revalidação OK — peso e códigos conferem. Clique em Finalizar pedido.");
            } else {
                notifyStep(WorkflowStep.VALIDATE_ORDER, validation.getSummaryMessage());
                if (config.isAiFallbackEnabled()
                        && context.getPhotoPath() != null && !context.getPhotoPath().isEmpty()) {
                    runAiAnalysis();
                }
            }

            if (listener != null) {
                listener.onOperatorReviewRequired(validation.getSummaryMessage(), context);
            }
        } catch (Exception e) {
            if (listener != null) {
                listener.onError(e.getMessage() != null ? e.getMessage() : "Erro na re-simulação", e);
            }
        } finally {
            cycleInProgress.set(false);
        }
    }

    private void runSimulatedWeighing(WorkflowMockScenario scenario) {
        try {
            int requiredMs = scenario.isFastStabilization()
                    ? WorkflowConfig.FAST_SIMULATION_STABILIZATION_MS
                    : config.getStabilizationMs();
            notifyStep(WorkflowStep.WEIGHING, "Simulando estabilização...");
            long start = System.currentTimeMillis();
            while (running.get()) {
                if (System.currentTimeMillis() - start >= requiredMs) {
                    break;
                }
                Thread.sleep(50);
            }
            if (!running.get()) {
                return;
            }
            runCycle(scenario.getWeightKg(), true, scenario);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            handleCycleFailure("Simulação interrompida", e);
        } catch (Exception e) {
            handleCycleFailure(e.getMessage() != null ? e.getMessage() : "Erro na simulação", e);
        }
    }

    private void handleCycleFailure(String message, Exception cause) {
        cycleInProgress.set(false);
        armed.set(false);
        operatorReview.set(false);
        rfidCollecting.set(false);
        stopRfidReading();
        resetStabilizationTracking();
        resetPhaseFlags();
        notifyPhaseStart();
        if (listener != null) {
            listener.onError(message, cause);
        }
    }

    private final PeripheralDataListener scaleListener = new PeripheralDataListener() {
        @Override
        public void onData(PeripheralDataEvent event) {
            if (!running.get() || event == null) {
                return;
            }
            double weightKg = parseWeight(event);
            lastGrossKg = weightKg;
            boolean stable = Boolean.TRUE.equals(event.getStable());
            lastGrossStable = stable;
            double netKg = toNetKg(weightKg);
            context.updateWeight(netKg, stable);
            // UI recebe o bruto; aplica tara na exibição.
            if (listener != null) {
                listener.onWeightUpdate(event);
            }

            if (taring.get() || operatorReview.get()) {
                return;
            }
            if (cycleInProgress.get() || waitingForNext.get() || !armed.get()) {
                return;
            }
            evaluateStabilization(netKg, stable);
        }

        @Override
        public void onError(Throwable error) {
            if (listener != null && error != null) {
                listener.onError(error.getMessage(), error);
            }
        }

        @Override
        public void onReadingStateChanged(boolean reading) {
        }
    };

    private final PeripheralDataListener continuousRfidListener = new PeripheralDataListener() {
        @Override
        public void onData(PeripheralDataEvent event) {
            if (!running.get() || event == null) {
                return;
            }
            if (hasTagIdentity(event)) {
                lastRfidSignalMs.set(System.currentTimeMillis());
            }
            if (!rfidCollecting.get()) {
                return;
            }
            boolean added = context.addTag(event.getEpc(), event.getCode());
            if (listener != null) {
                listener.onTagRead(event);
                if (added) {
                    notifyTagInventory();
                }
            }
            if (added) {
                maybeAutoStartWeighingIfTagsComplete();
            }
        }

        @Override
        public void onError(Throwable error) {
            if (listener != null && error != null) {
                listener.onError("RFID: " + error.getMessage(), error);
            }
            if (PeripheralSafeIo.looksLikeConnectionLoss(error)) {
                ReadablePeripheral rfid = sessionManager.getDevice(PeripheralSlot.RFID_READER);
                // Timeout/erro no stop/start com a porta ainda aberta NÃO é perda de antena —
                // desconectar aqui forçava reconfigurar RFID entre pedidos.
                if (rfid != null && rfid.isConnected()) {
                    return;
                }
                sessionManager.disconnect(PeripheralSlot.RFID_READER);
                handleCycleFailure("Conexão com o leitor RFID foi perdida",
                        error instanceof Exception ? (Exception) error : new PeripheralException(
                                error != null ? error.getMessage() : "RFID desconectado", error));
            }
        }

        @Override
        public void onReadingStateChanged(boolean reading) {
        }
    };

    private void evaluateStabilization(double weight, boolean stable) {
        if (!stable || weight <= WorkflowConfig.MIN_WEIGHT_KG) {
            resetStabilizationTracking();
            return;
        }
        lastStableWeight = weight;
        long now = System.currentTimeMillis();
        long since = stableSinceMs.get();
        if (since == 0) {
            stableSinceMs.set(now);
            notifyStabilizationProgress(0);
            return;
        }
        long elapsed = now - since;
        if (elapsed >= config.getStabilizationMs()) {
            if (stabilizationTriggered.compareAndSet(false, true)) {
                armed.set(false);
                resetStabilizationTracking();
                executor.submit(() -> runCycle(weight, stable, null));
            }
            return;
        }
        notifyStabilizationProgress(elapsed);
    }

    private void notifyStabilizationProgress(long elapsedMs) {
        if (listener == null) {
            return;
        }
        int requiredMs = config.getStabilizationMs();
        listener.onStabilizationProgress(String.format("Estabilizando... %.1f s / %.1f s",
                elapsedMs / 1000.0, requiredMs / 1000.0));
    }

    private void resetStabilizationTracking() {
        stableSinceMs.set(0);
        stabilizationTriggered.set(false);
    }

    private void runCycle(double netWeightKg, boolean stable, WorkflowMockScenario simulation) {
        if (!running.get() || !cycleInProgress.compareAndSet(false, true)) {
            return;
        }
        // Preserva tags acumuladas pelo RFID contínuo.
        context.beginCycle(netWeightKg, stable);
        context.setNumeroPedido(pedido.getNumero());
        PedidoVolume volume = pedido.getVolume(currentVolumeIndex);
        context.setVolumeIndex(volume != null ? volume.getIndice() : currentVolumeIndex + 1);
        context.setCurrentVolume(volume);
        try {
            if (!running.get()) {
                return;
            }

            Set<String> tagsSnapshot = context.snapshotTagCodes();
            notifyStep(WorkflowStep.VALIDATE_ORDER,
                    "Peso estável — validando " + tagsSnapshot.size()
                            + " tag(s) e peso...");
            PedidoValidationService.ValidationResult validation = validationService.validate(
                    volume,
                    tagsSnapshot,
                    netWeightKg,
                    config.getWeightTolerancePercent(),
                    config.getWeightToleranceKg());

            if (config.isDemoForceDivergence()) {
                validation = new PedidoValidationService.ValidationResult(
                        false,
                        PedidoValidationService.ValidationStatus.WEIGHT_MISMATCH,
                        Collections.singletonList("Divergência forçada (cenário demo)."),
                        Collections.emptyList(),
                        volume != null ? volume.getPesoEsperadoKg() : 0,
                        netWeightKg);
            }

            context.setValidationResult(validation);
            rfidCollecting.set(false);

            if (validation.isValid()) {
                // Pop-up de sucesso (com foto) só após captura — ver handleHappyPath.
                handleHappyPath(validation);
            } else {
                handleDivergencePath(validation);
            }
        } catch (Throwable e) {
            handleCycleFailure(e.getMessage() != null ? e.getMessage() : "Erro no fluxo",
                    e instanceof Exception ? (Exception) e : new PeripheralException(
                            e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(), e));
        }
    }

    private void handleHappyPath(PedidoValidationService.ValidationResult validation)
            throws IOException, PeripheralException {
        context.setValidationStatusLabel("APROVADO_AUTOMATICO");
        notifyStep(WorkflowStep.VALIDATE_ORDER, "Validação OK — peso e tags conferem.");

        if (config.isEnabled(WorkflowStep.PRINT_LABEL)) {
            printLabel();
        }
        if (config.isEnabled(WorkflowStep.CAPTURE_PHOTO)) {
            capturePhotoOptional();
        }
        // Notifica UI só depois da foto, para o pop-up de sucesso incluir a imagem.
        if (listener != null) {
            listener.onValidationResult(validation);
        }
        recordAndAdvance("APROVADO_AUTOMATICO");
    }

    private void handleDivergencePath(PedidoValidationService.ValidationResult validation) {
        context.setValidationStatusLabel("DIVERGENCIA");
        List<String> lines = new ArrayList<>(validation.getMessages());
        if (lines.isEmpty()) {
            lines.add("Divergência detectada");
        }

        cycleInProgress.set(false);
        armed.set(false);
        operatorReview.set(false);
        waitingForNext.set(false);
        rfidCollecting.set(false);
        stopRfidReading(); // soft: só se ainda estiver lendo

        context.setAiMessage(null);
        context.setMissingProducts(null);
        context.setUnexpectedProducts(null);
        context.setPhotoPath(null);
        // Zera inventário do pedido já na divergência — próximo ciclo começa do zero.
        context.clearTags();
        notifyTagInventory();

        // IA só entra se o fallback estiver configurado — e só após já haver divergência.
        if (config != null && config.isAiFallbackEnabled()) {
            if (listener != null) {
                listener.onAiAnalysisStarted("Analisando pedido...");
            }
            notifyStep(WorkflowStep.AI_ANALYSIS, "Analisando pedido...");
            try {
                CameraHardware.beginExclusiveCapture();
                try {
                    try {
                        capturePhotoMandatory();
                    } catch (Throwable e) {
                        lines.add("IA — foto indisponível: "
                                + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                    }
                    runAiAnalysis(false);
                    appendAiDivergenceLines(lines);
                } finally {
                    CameraHardware.endExclusiveCapture();
                }
            } finally {
                if (listener != null) {
                    listener.onAiAnalysisFinished();
                }
            }
        }

        String detail = String.join("\n", lines);
        notifyStep(WorkflowStep.VALIDATE_ORDER, detail.replace("\n", " | "));
        if (listener != null) {
            listener.onDivergenceOutcome(detail, context);
        }

        sleepQuietly(DIVERGENCE_OUTCOME_MESSAGE_MS);
        if (!running.get()) {
            return;
        }
        restartCurrentVolumeFromScratch(
                "Divergência — reiniciando o pedido. Aproxime os produtos novamente.");
    }

    private void appendAiDivergenceLines(List<String> lines) {
        List<String> missing = context.getMissingProducts();
        List<String> unexpected = context.getUnexpectedProducts();
        boolean addedStructured = false;
        if (missing != null && !missing.isEmpty()) {
            lines.add("IA — produtos faltando / não identificados: " + String.join(", ", missing));
            addedStructured = true;
        }
        if (unexpected != null && !unexpected.isEmpty()) {
            lines.add("IA — produtos fora do pedido: " + String.join(", ", unexpected));
            addedStructured = true;
        }
        if (addedStructured) {
            return;
        }
        // Sem listas estruturadas: só inclui se for falha de serviço/análise (não sucesso da IA).
        String aiMsg = context.getAiMessage();
        if (aiMsg != null && !aiMsg.trim().isEmpty() && looksLikeAiFailure(aiMsg)) {
            lines.add("IA — " + aiMsg.trim());
        }
    }

    private static boolean looksLikeAiFailure(String message) {
        String m = message.toLowerCase();
        return m.contains("indispon")
                || m.contains("erro")
                || m.contains("interrompid")
                || m.contains("falha")
                || m.contains("unavailable")
                || m.contains("falhou");
    }

    /**
     * Reinicia o ciclo do volume/pedido atual: limpa tags e volta à leitura RFID.
     * O gatilho de pesagem continua sendo “todas as tags do pedido identificadas”.
     */
    private void restartCurrentVolumeFromScratch(String message) {
        if (!running.get()) {
            return;
        }
        rfidTransitionToken.incrementAndGet();
        cycleInProgress.set(false);
        armed.set(false);
        operatorReview.set(false);
        waitingForNext.set(false);
        confirmingWeight.set(false);
        rfidCollecting.set(false);
        // Só limpa histórico de tags — não desconfigura a sessão RFID.
        context.clearTags();
        notifyTagInventory();
        resetStabilizationTracking();
        resetPhaseFlags();
        String restartMsg = message != null ? message
                : "Reiniciando leitura de tags do pedido...";
        notifyStep(WorkflowStep.RFID_READ, restartMsg);
        if (listener != null) {
            listener.onDivergenceRestart(restartMsg, context);
        }
        notifyPhaseStart();
    }

    private void runAiAnalysis() {
        runAiAnalysis(true);
    }

    /**
     * @param notifyUiPopup false no fluxo automático (resultado entra no pop-up unificado)
     */
    private void runAiAnalysis(boolean notifyUiPopup) {
        notifyStep(WorkflowStep.AI_ANALYSIS, "Analisando imagem (fallback)...");
        if (cameraClient == null || !cameraClient.isAvailable()) {
            context.setAiMessage("Serviço de IA indisponível.");
            notifyStep(WorkflowStep.AI_ANALYSIS, context.getAiMessage());
            if (notifyUiPopup) {
                notifyAiResult(false, context.getAiMessage());
            }
            return;
        }
        // Backend IMX500 RPK precisa da câmera livre (sem MJPEG). Mantém exclusividade
        // durante toda a análise — evita "Post-process IMX500 indisponível".
        // Preview já deve estar parado (exclusive do caller ou daqui).
        boolean exclusiveOwnedHere = !CameraHardware.isExclusiveCapture();
        if (exclusiveOwnedHere) {
            CameraHardware.beginExclusiveCapture();
        }
        try {
            Thread.sleep(1200);
            String imagePath = context.getPhotoPath() != null ? context.getPhotoPath() : "";
            List<CameraMicroserviceClient.ExpectedProductPayload> expected = buildExpectedProducts();
            CameraMicroserviceClient.AnalysisResult result = cameraClient.analyze(imagePath, expected);
            context.setAiMessage(result.getMessage());
            context.setMissingProducts(result.getMissingProducts());
            context.setUnexpectedProducts(result.getUnexpectedProducts());
            notifyStep(WorkflowStep.AI_ANALYSIS, result.getMessage());
            if (notifyUiPopup) {
                notifyAiResult(result.isProductsMatch(), result.getMessage());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            context.setAiMessage("Análise interrompida.");
            notifyStep(WorkflowStep.AI_ANALYSIS, context.getAiMessage());
            if (notifyUiPopup) {
                notifyAiResult(false, context.getAiMessage());
            }
        } catch (CameraServiceException e) {
            // Retry com câmera ainda exclusiva (preview não religa).
            try {
                Thread.sleep(1800);
                List<CameraMicroserviceClient.ExpectedProductPayload> expected = buildExpectedProducts();
                String imagePath = context.getPhotoPath() != null ? context.getPhotoPath() : "";
                CameraMicroserviceClient.AnalysisResult result =
                        cameraClient.analyze(imagePath, expected);
                context.setAiMessage(result.getMessage());
                context.setMissingProducts(result.getMissingProducts());
                context.setUnexpectedProducts(result.getUnexpectedProducts());
                notifyStep(WorkflowStep.AI_ANALYSIS, result.getMessage());
                if (notifyUiPopup) {
                    notifyAiResult(result.isProductsMatch(), result.getMessage());
                }
            } catch (Exception retryEx) {
                context.setAiMessage("Erro na análise: " + e.getMessage());
                notifyStep(WorkflowStep.AI_ANALYSIS, context.getAiMessage());
                if (notifyUiPopup) {
                    notifyAiResult(false, context.getAiMessage());
                }
            }
        } finally {
            if (exclusiveOwnedHere) {
                CameraHardware.endExclusiveCapture();
            }
        }
    }

    private void notifyAiResult(boolean identified, String message) {
        if (listener != null) {
            listener.onAiAnalysisResult(identified, message, context);
        }
    }

    private List<CameraMicroserviceClient.ExpectedProductPayload> buildExpectedProducts() {
        List<CameraMicroserviceClient.ExpectedProductPayload> list = new ArrayList<>();
        PedidoVolume volume = context.getCurrentVolume();
        if (volume == null) {
            return list;
        }
        for (PedidoItem item : volume.getItens()) {
            list.add(new CameraMicroserviceClient.ExpectedProductPayload(
                    item.getCodigoProduto(),
                    item.getNome(),
                    item.getQuantidadeEsperada(),
                    pedido.getNumero(),
                    context.getVolumeIndex()));
        }
        return list;
    }

    private void enterOperatorReview(String message) {
        operatorReview.set(true);
        cycleInProgress.set(false);
        armed.set(false);
        awaitingTagStart.set(false);
        awaitingWeightStart.set(false);
        waitingForNext.set(false);
        notifyStep(WorkflowStep.OPERATOR_REVIEW, message);
        if (listener != null) {
            listener.onOperatorReviewRequired(message, context);
        }
    }

    private void revalidateDuringReview() throws PeripheralException {
        PedidoVolume volume = context.getCurrentVolume();
        PedidoValidationService.ValidationResult validation = validationService.validate(
                volume,
                context.snapshotTagCodes(),
                context.getWeightKg(),
                config.getWeightTolerancePercent(),
                config.getWeightToleranceKg());
        context.setValidationResult(validation);
        if (listener != null) {
            listener.onValidationResult(validation);
        }
        notifyStep(WorkflowStep.VALIDATE_ORDER, validation.getSummaryMessage());
    }

    private void recordAndAdvance(String statusLabel) {
        context.setValidationStatusLabel(statusLabel);
        cycleInProgress.set(false);
        if (listener != null) {
            WorkflowReadingRecord record = sessionStore.addReading(context);
            listener.onReadingRecorded(record);
            listener.onCycleCompleted(context);
        }
        // Tempo do pop-up de sucesso (com foto) antes de avançar / carregar o próximo.
        long outcomeMs = context.getPhotoPath() != null && !context.getPhotoPath().trim().isEmpty()
                ? 2800
                : OUTCOME_MESSAGE_MS;
        sleepQuietly(outcomeMs);
        if (!running.get()) {
            return;
        }
        advanceVolumeOrComplete();
    }

    private void advanceVolumeOrComplete() {
        currentVolumeIndex++;
        if (currentVolumeIndex >= pedido.getVolumeCount()) {
            finishCurrentPedidoAndAdvance();
            return;
        }
        String prepareMsg = "Retire os produtos já conferidos. Carregando próximo volume...";
        if (listener != null) {
            listener.onPreparingNextPedido(pedido, pedido,
                    pedidoIndex + 1, pedidos.size(), prepareMsg);
        }
        notifyStep(WorkflowStep.FETCH_ORDER, prepareMsg);
        sleepQuietly(NEXT_PEDIDO_DELAY_MS);
        if (!running.get()) {
            return;
        }
        armed.set(false);
        operatorReview.set(false);
        waitingForNext.set(false);
        rfidCollecting.set(false);
        // Só limpa histórico de tags após o aguardo — sem stop/reconfig do RFID.
        context.clearTags();
        notifyTagInventory();
        resetStabilizationTracking();
        resetPhaseFlags();
        if (listener != null) {
            listener.onVolumeChanged(currentVolumeIndex + 1, pedido.getVolumeCount());
        }
        notifyStep(WorkflowStep.FETCH_ORDER,
                "Volume " + (currentVolumeIndex + 1) + " — tags limpas. Aproxime os produtos.");
        notifyPhaseStart();
    }

    /** Pedido atual concluído → próximo da fila (do zero) ou volta ao primeiro (ciclo contínuo). */
    private void finishCurrentPedidoAndAdvance() {
        Pedido finished = pedido;
        armed.set(false);
        operatorReview.set(false);
        waitingForNext.set(false);
        cycleInProgress.set(false);
        rfidCollecting.set(false);
        // RFID já parado na pesagem — não chama stop de novo (evita perda de conexão).
        cancelTareMeasurement();

        if (listener != null) {
            listener.onOrderCompleted(finished);
        }

        pedidoIndex++;
        boolean wrapped = false;
        if (pedidoIndex >= pedidos.size()) {
            // Fila completa: reinicia do primeiro pedido e espera o mesmo gatilho de tags.
            pedidoIndex = 0;
            wrapped = true;
        }
        pedido = pedidos.get(pedidoIndex);
        currentVolumeIndex = 0;

        String prepareMsg = wrapped
                ? "Fila concluída — reiniciando do primeiro pedido. Retire os produtos já conferidos."
                : "Retire os produtos já conferidos. Carregando próximo pedido...";
        if (listener != null) {
            listener.onPreparingNextPedido(finished, pedido,
                    pedidoIndex + 1, pedidos.size(), prepareMsg);
        }
        notifyStep(WorkflowStep.FETCH_ORDER, prepareMsg);

        sleepQuietly(NEXT_PEDIDO_DELAY_MS);
        if (!running.get()) {
            return;
        }

        // Limpa só o histórico de tags após o aguardo — sessão RFID permanece.
        context.clearTags();
        notifyTagInventory();
        resetStabilizationTracking();
        resetPhaseFlags();

        if (listener != null) {
            listener.onOrderLoaded(pedido);
            listener.onVolumeChanged(1, pedido.getVolumeCount());
            listener.onOrderQueueUpdated(pedidoIndex + 1, pedidos.size());
            listener.onNextPedidoStarted(finished, pedido, pedidoIndex + 1, pedidos.size());
        }
        notifyStep(WorkflowStep.FETCH_ORDER,
                "Pedido " + finished.getNumero() + " OK — iniciando "
                        + pedido.getNumero() + " (" + (pedidoIndex + 1) + "/" + pedidos.size()
                        + "). Tags limpas — aproxime os produtos.");
        notifyPhaseStart();
    }

    /**
     * Quando todas as tags esperadas do pedido/volume atual estão presentes,
     * desliga o RFID e arma a estabilização do peso automaticamente.
     */
    private void maybeAutoStartWeighingIfTagsComplete() {
        if (!running.get() || !rfidCollecting.get() || armed.get() || cycleInProgress.get()
                || operatorReview.get() || taring.get() || waitingForNext.get()
                || confirmingWeight.get()) {
            return;
        }
        PedidoVolume volume = pedido != null ? pedido.getVolume(currentVolumeIndex) : null;
        if (volume == null) {
            return;
        }
        if (!validationService.areExpectedTagsComplete(volume, context.snapshotTagCodes())) {
            return;
        }
        notifyStep(WorkflowStep.RFID_READ,
                "Todas as tags do pedido identificadas — iniciando pesagem...");
        confirmWeighingStart();
    }

    private void sleepQuietly(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            long remaining = ms;
            while (remaining > 0 && running.get()) {
                long slice = Math.min(200, remaining);
                Thread.sleep(slice);
                remaining -= slice;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean hasTagIdentity(PeripheralDataEvent event) {
        return event != null
                && ((event.getEpc() != null && !event.getEpc().trim().isEmpty())
                || (event.getCode() != null && !event.getCode().trim().isEmpty()));
    }

    private void startContinuousRfidIfEnabled() throws PeripheralException {
        if (config == null || !config.isEnabled(WorkflowStep.RFID_READ)) {
            return;
        }
        ensureRfidConnected();
        ReadablePeripheral rfid = sessionManager.getDevice(PeripheralSlot.RFID_READER);
        if (rfid == null || !rfid.isConnected()) {
            throw new PeripheralException("Leitor RFID não conectado");
        }
        // Já inventariando: mantém a sessão — não faz stop/start (causa timeout/desconexão).
        if (rfid.isReading()) {
            return;
        }
        rfid.startContinuousReading(continuousRfidListener);
    }

    /**
     * Soft-stop do inventário (pesagem) nunca deveria derrubar a sessão; se ainda assim
     * a conexão caiu, reconecta com a mesma porta/modelo da tela de configuração.
     */
    private void ensureRfidConnected() throws PeripheralException {
        if (sessionManager.isConnected(PeripheralSlot.RFID_READER)) {
            return;
        }
        PeripheralConnectionHandle handle = sessionManager.getHandle(PeripheralSlot.RFID_READER);
        DeviceModelEntry model = handle != null ? handle.getModel() : null;
        SerialConnectionConfig cfg = handle != null ? handle.getSerialConfig() : null;
        if (model == null || cfg == null
                || cfg.getPortName() == null || cfg.getPortName().trim().isEmpty()) {
            throw new PeripheralException("Leitor RFID não conectado");
        }
        int power = 100;
        int[] antennas = new int[]{1};
        ReadablePeripheral existing = handle.getDevice();
        if (existing instanceof RfidConfigurable) {
            RfidConfigurable rc = (RfidConfigurable) existing;
            int p = rc.getPowerPercent();
            if (p > 0) {
                power = p;
            }
            int[] ids = rc.getAntennaIds();
            if (ids != null && ids.length > 0) {
                antennas = ids;
            }
        }
        notifyStep(WorkflowStep.RFID_READ,
                "Reconectando RFID em " + cfg.getPortName().trim() + "...");
        sessionManager.connect(PeripheralSlot.RFID_READER, model, cfg, power, antennas);
    }

    private boolean isRfidEnabled() {
        return config != null && config.isEnabled(WorkflowStep.RFID_READ);
    }

    private void resetPhaseFlags() {
        if (isRfidEnabled()) {
            awaitingTagStart.set(true);
            awaitingWeightStart.set(false);
        } else {
            awaitingTagStart.set(false);
            awaitingWeightStart.set(true);
        }
    }

    private void notifyPhaseStart() {
        if (isRfidEnabled()) {
            // RFID começa sozinho ao abrir/avançar — operador só clica para pesar.
            confirmTagReadingStart();
            return;
        }
        if (listener != null) {
            listener.onAwaitingWeighingStart();
        }
    }

    private void injectSimulatedTags(WorkflowMockScenario scenario) {
        if (scenario == null) {
            return;
        }
        notifyStep(WorkflowStep.RFID_READ, "Simulando detecção contínua de tags...");
        for (WorkflowMockScenario.MockTag tag : scenario.getTags()) {
            if (!running.get()) {
                return;
            }
            boolean added = context.addTag(tag.getEpc(), tag.getCode());
            if (listener != null) {
                listener.onTagRead(PeripheralDataEvent.builder(null)
                        .code(tag.getCode())
                        .epc(tag.getEpc())
                        .build());
                if (added) {
                    notifyTagInventory();
                }
            }
        }
        maybeAutoStartWeighingIfTagsComplete();
    }

    private void notifyTagInventory() {
        if (listener != null) {
            // UI só mostra tags lidas — o total esperado fica só na validação interna.
            listener.onTagInventoryUpdated(context.listDetectedCodes(), 0);
        }
    }

    private void capturePhotoOptional() {
        CameraHardware.beginExclusiveCapture();
        try {
            notifyStep(WorkflowStep.CAPTURE_PHOTO, "Capturando foto (opcional)...");
            photoCapture().capturePhoto(context, sessionStore.getSessionDirectory(),
                    sessionStore.getNextPhotoIndex(), false);
            notifyStep(WorkflowStep.CAPTURE_PHOTO, "Foto salva: " + context.getPhotoPath());
        } catch (Throwable e) {
            notifyStep(WorkflowStep.CAPTURE_PHOTO,
                    "Foto não capturada: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        } finally {
            CameraHardware.endExclusiveCapture();
        }
    }

    private void capturePhotoMandatory() throws IOException {
        CameraHardware.beginExclusiveCapture();
        try {
            notifyStep(WorkflowStep.CAPTURE_PHOTO, "Capturando foto (divergência)...");
            photoCapture().capturePhoto(context, sessionStore.getSessionDirectory(),
                    sessionStore.getNextPhotoIndex(), true);
            notifyStep(WorkflowStep.CAPTURE_PHOTO, "Foto salva: " + context.getPhotoPath());
        } finally {
            CameraHardware.endExclusiveCapture();
        }
    }

    private void printLabel() throws IOException, PeripheralException {
        int labelIndex = sessionStore.getNextLabelIndex();
        notifyStep(WorkflowStep.PRINT_LABEL, "Gerando etiqueta PDF...");
        labelPrint().generateLabelPdf(context, sessionStore.getSessionDirectory(), labelIndex);
        notifyStep(WorkflowStep.PRINT_LABEL, "Imprimindo etiqueta (PDF → ZPL)...");
        labelPrint().printLabel(context, sessionStore.getSessionDirectory(), labelIndex);
    }

    private void stopScaleReading() {
        ReadablePeripheral scale = sessionManager.getDevice(PeripheralSlot.SCALE);
        if (scale != null) {
            PeripheralSafeIo.stopReading(scale);
        }
    }

    private void stopRfidReading() {
        ReadablePeripheral rfid = sessionManager.getDevice(PeripheralSlot.RFID_READER);
        // Soft-stop: só inventário. Timeout um pouco maior que o Mercury (não faz destroy).
        if (rfid != null && rfid.isReading()) {
            PeripheralSafeIo.stopReading(rfid, 4_000L);
        }
    }

    private double parseWeight(PeripheralDataEvent event) {
        Double fromField = ScaleWeightFormat.parseKg(event.getWeight());
        if (fromField != null) {
            return fromField;
        }
        DigitronDgnParser.ParseResult parsed = DigitronDgnParser.parse(event.getRawPayload());
        if (parsed.isParsed()) {
            return parsed.getWeightKg();
        }
        return 0;
    }

    private double toNetKg(double grossKg) {
        return Math.max(0, grossKg - Math.max(0, tareKg));
    }

    private void notifyStep(WorkflowStep step, String message) {
        if (listener != null) {
            listener.onStepChanged(step, message);
        }
    }

    private void notifyCameraStatus() {
        if (listener == null) {
            return;
        }
        boolean serviceOk = cameraClient != null && cameraClient.isAvailable();
        boolean hardwareOk = com.peripheral.camera.CameraHardware.isCameraPresent();
        boolean available = serviceOk || hardwareOk;
        String detail;
        if (hardwareOk) {
            detail = "Sony IMX500 — disponível";
        } else if (serviceOk) {
            detail = "online";
        } else {
            detail = "indisponível";
        }
        listener.onCameraServiceStatus(available, detail);
    }

    private PhotoCaptureService photoCapture() {
        if (photoCaptureService == null) {
            photoCaptureService = new PhotoCaptureService(cameraClient);
        }
        return photoCaptureService;
    }

    private LabelPrintService labelPrint() throws PeripheralException {
        if (labelPrintService == null) {
            try {
                labelPrintService = new LabelPrintService();
            } catch (NoClassDefFoundError | ExceptionInInitializerError e) {
                throw new PeripheralException(
                        "Bibliotecas PDF não disponíveis (pdfbox/fontbox). Execute com ./iniciar.sh no Linux.");
            }
        }
        return labelPrintService;
    }
}
