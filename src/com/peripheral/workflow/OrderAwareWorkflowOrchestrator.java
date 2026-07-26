package com.peripheral.workflow;

import com.peripheral.camera.CameraHardware;
import com.peripheral.camera.CameraMicroserviceClient;
import com.peripheral.camera.CameraServiceException;
import com.peripheral.core.PeripheralDataEvent;
import com.peripheral.core.PeripheralDataListener;
import com.peripheral.core.PeripheralException;
import com.peripheral.core.PeripheralSafeIo;
import com.peripheral.core.ReadablePeripheral;
import com.peripheral.pedido.Pedido;
import com.peripheral.pedido.PedidoItem;
import com.peripheral.pedido.PedidoVolume;
import com.peripheral.scale.DigitronDgnParser;
import com.peripheral.scale.ScaleWeightFormat;
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
import java.util.concurrent.atomic.AtomicLong;

public class OrderAwareWorkflowOrchestrator implements WorkflowController {

    private final PeripheralSessionManager sessionManager;
    private final Pedido pedido;
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

    public OrderAwareWorkflowOrchestrator(PeripheralSessionManager sessionManager,
                                          Pedido pedido,
                                          CameraMicroserviceClient cameraClient) {
        this.sessionManager = sessionManager;
        this.pedido = pedido;
        this.cameraClient = cameraClient;
    }

    @Override
    public void start(WorkflowConfig config, WorkflowListener listener) throws PeripheralException {
        if (pedido == null || pedido.getVolumeCount() == 0) {
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
        currentVolumeIndex = 0;
        waitingForNext.set(false);
        operatorReview.set(false);
        armed.set(false);
        cycleInProgress.set(false);
        rfidCollecting.set(false);
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
            listener.onVolumeChanged(currentVolumeIndex + 1, pedido.getVolumeCount());
            listener.onTagInventoryUpdated(Collections.emptyList(), 0);
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
            handleCycleFailure(e.getMessage(), e);
            return;
        }
        notifyStep(WorkflowStep.RFID_READ, "Lendo tags — aproxime os produtos; depois inicie a pesagem");
        if (listener != null) {
            listener.onTagReadingInProgress();
        }
    }

    @Override
    public void confirmWeighingStart() {
        if (!running.get() || operatorReview.get()) {
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
                : "Coloque o item na balança — aguardando estabilização (1,5 s)...";
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
        context.setOperatorConfirmed(true);
        operatorReview.set(false);
        waitingForNext.set(false);
        cycleInProgress.set(false);
        rfidCollecting.set(false);
        recordAndAdvance("APROVADO_OPERADOR");
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
            boolean stable = Boolean.TRUE.equals(event.getStable());
            context.updateWeight(weightKg, stable);
            // Mesmo evento da tela de configuração — sem tara lógica.
            if (listener != null) {
                listener.onWeightUpdate(event);
            }

            if (operatorReview.get()) {
                return;
            }
            if (cycleInProgress.get() || waitingForNext.get() || !armed.get()) {
                return;
            }
            evaluateStabilization(weightKg, stable);
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
            if (!running.get() || event == null || !rfidCollecting.get()) {
                return;
            }
            boolean added = context.addTag(event.getEpc(), event.getCode());
            if (listener != null) {
                listener.onTagRead(event);
                if (added) {
                    notifyTagInventory();
                }
            }
        }

        @Override
        public void onError(Throwable error) {
            if (listener != null && error != null) {
                listener.onError("RFID: " + error.getMessage(), error);
            }
            if (PeripheralSafeIo.looksLikeConnectionLoss(error)) {
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
            if (listener != null) {
                listener.onValidationResult(validation);
            }

            rfidCollecting.set(false);

            if (validation.isValid()) {
                handleHappyPath();
            } else {
                handleDivergencePath(validation);
            }
        } catch (Throwable e) {
            handleCycleFailure(e.getMessage() != null ? e.getMessage() : "Erro no fluxo",
                    e instanceof Exception ? (Exception) e : new PeripheralException(
                            e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(), e));
        }
    }

    private void handleHappyPath() throws IOException, PeripheralException {
        context.setValidationStatusLabel("APROVADO_AUTOMATICO");
        notifyStep(WorkflowStep.VALIDATE_ORDER, "Validação OK — peso e tags conferem.");

        if (config.isEnabled(WorkflowStep.PRINT_LABEL)) {
            printLabel();
        }
        if (config.isEnabled(WorkflowStep.CAPTURE_PHOTO)) {
            capturePhotoOptional();
        }
        recordAndAdvance("APROVADO_AUTOMATICO");
    }

    private void handleDivergencePath(PedidoValidationService.ValidationResult validation)
            throws IOException, PeripheralException {
        context.setValidationStatusLabel("DIVERGENCIA");
        notifyStep(WorkflowStep.VALIDATE_ORDER, validation.getSummaryMessage());

        // Sem IA de fallback: sem foto/análise de vídeo — revisão 100% do operador.
        if (config.isAiFallbackEnabled()) {
            try {
                capturePhotoMandatory();
            } catch (Throwable e) {
                // Nunca derruba o fluxo / fecha a janela por falha de câmera.
                notifyStep(WorkflowStep.CAPTURE_PHOTO,
                        "Foto indisponível: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())
                                + " — continue com revisão manual.");
            }
        }

        if (config.isEnabled(WorkflowStep.PRINT_LABEL)) {
            printLabel();
        }

        if (config.isAiFallbackEnabled()) {
            runAiAnalysis();
        } else {
            context.setAiMessage(null);
            notifyStep(WorkflowStep.OPERATOR_REVIEW,
                    "Divergência — revise manualmente: reler tags, liberar volume ou reiniciar.");
        }
        enterOperatorReview(validation.getSummaryMessage());
    }

    private void runAiAnalysis() {
        notifyStep(WorkflowStep.AI_ANALYSIS, "Analisando imagem (fallback)...");
        if (cameraClient == null || !cameraClient.isAvailable()) {
            context.setAiMessage("Serviço de IA indisponível — revise manualmente.");
            notifyStep(WorkflowStep.AI_ANALYSIS, context.getAiMessage());
            return;
        }
        if (context.getPhotoPath() == null || context.getPhotoPath().isEmpty()) {
            context.setAiMessage("Sem foto para análise — revise manualmente.");
            return;
        }
        try {
            List<CameraMicroserviceClient.ExpectedProductPayload> expected = buildExpectedProducts();
            CameraMicroserviceClient.AnalysisResult result = cameraClient.analyze(
                    context.getPhotoPath(), expected);
            context.setAiMessage(result.getMessage());
            context.setMissingProducts(result.getMissingProducts());
            notifyStep(WorkflowStep.AI_ANALYSIS, result.getMessage());
        } catch (CameraServiceException e) {
            context.setAiMessage("Erro na análise: " + e.getMessage());
            notifyStep(WorkflowStep.AI_ANALYSIS, context.getAiMessage());
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
        advanceVolumeOrComplete();
    }

    private void advanceVolumeOrComplete() {
        currentVolumeIndex++;
        if (currentVolumeIndex >= pedido.getVolumeCount()) {
            if (listener != null) {
                listener.onOrderCompleted(pedido);
            }
            notifyStep(WorkflowStep.FETCH_ORDER, "Pedido " + pedido.getNumero() + " concluído.");
            stop();
            return;
        }
        armed.set(false);
        operatorReview.set(false);
        waitingForNext.set(false);
        rfidCollecting.set(false);
        context.clearTags();
        stopRfidReading();
        notifyTagInventory();
        resetStabilizationTracking();
        resetPhaseFlags();
        if (listener != null) {
            listener.onVolumeChanged(currentVolumeIndex + 1, pedido.getVolumeCount());
        }
        notifyPhaseStart();
    }

    private void startContinuousRfidIfEnabled() throws PeripheralException {
        if (config == null || !config.isEnabled(WorkflowStep.RFID_READ)) {
            return;
        }
        ReadablePeripheral rfid = sessionManager.getDevice(PeripheralSlot.RFID_READER);
        if (rfid == null || !rfid.isConnected()) {
            throw new PeripheralException("Leitor RFID não conectado");
        }
        if (rfid.isReading()) {
            PeripheralSafeIo.stopReading(rfid);
        }
        rfid.startContinuousReading(continuousRfidListener);
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
        if (listener == null) {
            return;
        }
        if (awaitingTagStart.get()) {
            listener.onAwaitingTagReadingStart();
        } else {
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
        if (rfid != null) {
            PeripheralSafeIo.stopReading(rfid);
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
