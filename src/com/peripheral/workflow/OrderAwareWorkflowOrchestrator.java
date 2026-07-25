package com.peripheral.workflow;

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
import com.peripheral.session.PeripheralSessionManager;
import com.peripheral.session.PeripheralSlot;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
    private final AtomicBoolean awaitingUserStart = new AtomicBoolean(false);
    private final AtomicBoolean operatorReview = new AtomicBoolean(false);
    private final AtomicLong stableSinceMs = new AtomicLong(0);
    private final AtomicBoolean stabilizationTriggered = new AtomicBoolean(false);

    private int currentVolumeIndex;
    private double lastStableWeight;

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
        awaitingUserStart.set(true);
        resetStabilizationTracking();

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
            scale.startContinuousReading(scaleListener);
        }
        notifyAwaitingWeighingStart();
    }

    @Override
    public void stop() {
        running.set(false);
        cycleInProgress.set(false);
        armed.set(false);
        waitingForNext.set(false);
        awaitingUserStart.set(false);
        operatorReview.set(false);
        resetStabilizationTracking();
        stopScaleReading();
        stopRfidReading();
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
        awaitingUserStart.set(true);
        resetStabilizationTracking();
        sessionStore.clearSession();
        try {
            sessionStore.beginSession();
        } catch (IOException e) {
            throw new PeripheralException("Não foi possível reiniciar a sessão: " + e.getMessage());
        }
        if (listener != null) {
            listener.onSessionCleared();
            listener.onVolumeChanged(currentVolumeIndex + 1, pedido.getVolumeCount());
            listener.onAwaitingWeighingStart();
        }
    }

    @Override
    public WorkflowSessionStore getSessionStore() {
        return sessionStore;
    }

    @Override
    public void confirmWeighingStart() {
        if (!running.get() || operatorReview.get()) {
            return;
        }
        if (!awaitingUserStart.compareAndSet(true, false)) {
            return;
        }
        armed.set(true);
        resetStabilizationTracking();
        PedidoVolume volume = pedido.getVolume(currentVolumeIndex);
        context.setNumeroPedido(pedido.getNumero());
        context.setVolumeIndex(volume != null ? volume.getIndice() : currentVolumeIndex + 1);
        context.setCurrentVolume(volume);
        String message = config.isSimulationMode()
                ? "Modo simulação — clique em Simular pesagem estável"
                : "Volume " + (currentVolumeIndex + 1) + "/" + pedido.getVolumeCount()
                + " — aguardando estabilização (1,5 s)...";
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
        recordAndAdvance("APROVADO_OPERADOR");
    }

    @Override
    public void operatorRereadRfid() throws PeripheralException {
        if (!operatorReview.get()) {
            return;
        }
        context.clearTags();
        if (config.isSimulationMode()) {
            notifyStep(WorkflowStep.RFID_READ,
                    "Seriais limpos — ajuste peso/seriais e clique em Simular pesagem estável.");
            return;
        }
        try {
            runRfidBurst();
            revalidateDuringReview();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PeripheralException("Leitura RFID interrompida");
        }
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
        if (!operatorReview.get()) {
            return;
        }
        runAiAnalysis();
    }

    private void runSimulatedOperatorRetry(WorkflowMockScenario scenario) {
        if (!operatorReview.get() || !cycleInProgress.compareAndSet(false, true)) {
            return;
        }
        try {
            notifyStep(WorkflowStep.WEIGHING, "Re-simulando peso e seriais...");
            context.clearTags();
            context.updateWeight(scenario.getWeightKg(), true);
            if (listener != null) {
                listener.onWeightUpdate(PeripheralDataEvent.builder(null)
                        .weight(String.format(java.util.Locale.US, "%.3f", scenario.getWeightKg()))
                        .stable(true)
                        .build());
            }

            if (config.isEnabled(WorkflowStep.RFID_READ)) {
                runSimulatedRfidBurst(scenario);
            }

            PedidoVolume volume = context.getCurrentVolume();
            PedidoValidationService.ValidationResult validation = validationService.validate(
                    volume,
                    context.getTagCodes(),
                    scenario.getWeightKg(),
                    config.getWeightTolerancePercent(),
                    config.getWeightToleranceKg());

            context.setValidationResult(validation);
            if (listener != null) {
                listener.onValidationResult(validation);
            }

            if (validation.isValid()) {
                context.setValidationStatusLabel("REVALIDADO_OK");
                notifyStep(WorkflowStep.VALIDATE_ORDER,
                        "Revalidação OK — peso e seriais conferem. Clique em Finalizar volume.");
            } else {
                notifyStep(WorkflowStep.VALIDATE_ORDER, validation.getSummaryMessage());
                if (context.getPhotoPath() != null && !context.getPhotoPath().isEmpty()) {
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
        awaitingUserStart.set(true);
        resetStabilizationTracking();
        notifyAwaitingWeighingStart();
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
            // Monitor da balança: sempre atualiza a UI em tempo real
            if (listener != null) {
                listener.onWeightUpdate(event);
            }
            if (operatorReview.get()) {
                double weight = parseWeight(event);
                boolean stable = Boolean.TRUE.equals(event.getStable());
                context.updateWeight(weight, stable);
                return;
            }
            if (cycleInProgress.get() || waitingForNext.get() || !armed.get()) {
                return;
            }
            double weight = parseWeight(event);
            boolean stable = Boolean.TRUE.equals(event.getStable());
            context.updateWeight(weight, stable);
            evaluateStabilization(weight, stable);
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

    private void runCycle(double weightKg, boolean stable, WorkflowMockScenario simulation) {
        if (!running.get() || !cycleInProgress.compareAndSet(false, true)) {
            return;
        }
        context.beginCycle(weightKg, stable);
        context.setNumeroPedido(pedido.getNumero());
        PedidoVolume volume = pedido.getVolume(currentVolumeIndex);
        context.setVolumeIndex(volume != null ? volume.getIndice() : currentVolumeIndex + 1);
        context.setCurrentVolume(volume);
        try {
            if (config.isEnabled(WorkflowStep.RFID_READ)) {
                if (simulation != null) {
                    runSimulatedRfidBurst(simulation);
                } else {
                    runRfidBurst();
                }
            }
            if (!running.get()) {
                return;
            }

            notifyStep(WorkflowStep.VALIDATE_ORDER, "Validando peso e tags contra o pedido...");
            PedidoValidationService.ValidationResult validation = validationService.validate(
                    volume,
                    context.getTagCodes(),
                    weightKg,
                    config.getWeightTolerancePercent(),
                    config.getWeightToleranceKg());

            if (config.isDemoForceDivergence()) {
                validation = new PedidoValidationService.ValidationResult(
                        false,
                        PedidoValidationService.ValidationStatus.WEIGHT_MISMATCH,
                        java.util.Collections.singletonList("Divergência forçada (cenário demo)."),
                        java.util.Collections.emptyList(),
                        volume != null ? volume.getPesoEsperadoKg() : 0,
                        weightKg);
            }

            context.setValidationResult(validation);
            if (listener != null) {
                listener.onValidationResult(validation);
            }

            if (validation.isValid()) {
                handleHappyPath();
            } else {
                handleDivergencePath(validation);
            }
        } catch (Exception e) {
            handleCycleFailure(e.getMessage() != null ? e.getMessage() : "Erro no fluxo", e);
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

        try {
            capturePhotoMandatory();
        } catch (Exception e) {
            notifyStep(WorkflowStep.CAPTURE_PHOTO,
                    "Foto indisponível: " + e.getMessage() + " — continue com revisão manual.");
        }

        if (config.isEnabled(WorkflowStep.PRINT_LABEL)) {
            printLabel();
        }

        runAiAnalysis();
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
        awaitingUserStart.set(false);
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
                context.getTagCodes(),
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
        awaitingUserStart.set(true);
        operatorReview.set(false);
        waitingForNext.set(false);
        resetStabilizationTracking();
        if (listener != null) {
            listener.onVolumeChanged(currentVolumeIndex + 1, pedido.getVolumeCount());
            listener.onAwaitingWeighingStart();
        }
    }

    private void runSimulatedRfidBurst(WorkflowMockScenario scenario) throws InterruptedException {
        notifyStep(WorkflowStep.RFID_READ, "Simulando leitura RFID...");
        List<WorkflowMockScenario.MockTag> tags = scenario.getTags();
        int durationMs = Math.min(config.getRfidReadDurationMs(), 400);
        if (tags.isEmpty()) {
            Thread.sleep(durationMs);
            return;
        }
        long intervalMs = Math.max(80, durationMs / Math.max(1, tags.size()));
        for (WorkflowMockScenario.MockTag tag : tags) {
            if (!running.get()) {
                return;
            }
            context.addTag(tag.getEpc(), tag.getCode());
            if (listener != null) {
                listener.onTagRead(PeripheralDataEvent.builder(null)
                        .code(tag.getCode())
                        .epc(tag.getEpc())
                        .build());
            }
            Thread.sleep(intervalMs);
        }
    }

    private void runRfidBurst() throws PeripheralException, InterruptedException {
        ReadablePeripheral rfid = sessionManager.getDevice(PeripheralSlot.RFID_READER);
        if (rfid == null || !rfid.isConnected()) {
            throw new PeripheralException("Leitor RFID não conectado");
        }
        notifyStep(WorkflowStep.RFID_READ,
                "Lendo tags RFID por " + (config.getRfidReadDurationMs() / 1000) + " s...");
        PeripheralDataListener tagListener = new PeripheralDataListener() {
            @Override
            public void onData(PeripheralDataEvent event) {
                if (event == null) {
                    return;
                }
                context.addTag(event.getEpc(), event.getCode());
                if (listener != null) {
                    listener.onTagRead(event);
                }
            }

            @Override
            public void onError(Throwable error) {
                if (listener != null && error != null) {
                    listener.onError("RFID: " + error.getMessage(), error);
                }
            }

            @Override
            public void onReadingStateChanged(boolean reading) {
            }
        };
            rfid.startContinuousReading(tagListener);
        try {
            Thread.sleep(config.getRfidReadDurationMs());
        } finally {
            PeripheralSafeIo.stopReading(rfid);
        }
        if (!rfid.isConnected()) {
            sessionManager.disconnect(PeripheralSlot.RFID_READER);
            throw new PeripheralException(
                    "Conexão com o leitor RFID foi perdida. Reconecte o dispositivo e tente novamente.");
        }
    }

    private void capturePhotoOptional() throws IOException {
        notifyStep(WorkflowStep.CAPTURE_PHOTO, "Capturando foto (opcional)...");
        try {
            photoCapture().capturePhoto(context, sessionStore.getSessionDirectory(),
                    sessionStore.getNextPhotoIndex(), false);
            notifyStep(WorkflowStep.CAPTURE_PHOTO, "Foto salva: " + context.getPhotoPath());
        } catch (IOException e) {
            notifyStep(WorkflowStep.CAPTURE_PHOTO, "Foto não capturada: " + e.getMessage());
        }
    }

    private void capturePhotoMandatory() throws IOException {
        notifyStep(WorkflowStep.CAPTURE_PHOTO, "Capturando foto (divergência)...");
        photoCapture().capturePhoto(context, sessionStore.getSessionDirectory(),
                sessionStore.getNextPhotoIndex(), true);
        notifyStep(WorkflowStep.CAPTURE_PHOTO, "Foto salva: " + context.getPhotoPath());
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
        if (event.getWeight() != null && !event.getWeight().isEmpty()) {
            try {
                return Double.parseDouble(event.getWeight().trim());
            } catch (NumberFormatException ignored) {
            }
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

    private void notifyAwaitingWeighingStart() {
        if (listener != null) {
            listener.onAwaitingWeighingStart();
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
