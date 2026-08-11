package com.peripheral.workflow;

import com.peripheral.core.PeripheralDataEvent;
import com.peripheral.core.PeripheralDataListener;
import com.peripheral.core.PeripheralException;
import com.peripheral.core.PeripheralSafeIo;
import com.peripheral.core.ReadablePeripheral;
import com.peripheral.scale.DigitronDgnParser;
import com.peripheral.scale.ScaleWeightFormat;
import com.peripheral.session.PeripheralSessionManager;
import com.peripheral.session.PeripheralSlot;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fluxo em fases separadas:
 * 1) Iniciar leitura de tags (RF ligado)
 * 2) Iniciar leitura de peso (RF desligado → estabilização 1,5 s)
 */
public class WeighingWorkflowOrchestrator implements WorkflowController {

    /** Janela de estabilidade exigida para aceitar a tara. */
    private static final long TARE_STABLE_WINDOW_MS = 1200;
    private static final long TARE_TIMEOUT_MS = 15_000;
    private static final double TARE_STABLE_TOLERANCE_KG = 0.005;

    private final PeripheralSessionManager sessionManager;
    private PhotoCaptureService photoCaptureService;
    private LabelPrintService labelPrintService;
    private final WorkflowSessionStore sessionStore = new WorkflowSessionStore();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "weighing-workflow");
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
    private final AtomicBoolean rfidCollecting = new AtomicBoolean(false);
    private final AtomicLong stableSinceMs = new AtomicLong(0);
    private final AtomicBoolean stabilizationTriggered = new AtomicBoolean(false);
    private volatile double tareKg;
    private volatile double lastGrossKg;
    private volatile boolean lastGrossStable;
    private final AtomicBoolean taring = new AtomicBoolean(false);
    /** Invalida uma medição de tara em andamento quando a sessão muda. */
    private final AtomicInteger tareToken = new AtomicInteger();

    public WeighingWorkflowOrchestrator(PeripheralSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    public void start(WorkflowConfig config, WorkflowListener listener) throws PeripheralException {
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
        running.set(true);
        armed.set(false);
        waitingForNext.set(false);
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

        if (!config.isSimulationMode()) {
            ReadablePeripheral scale = sessionManager.getDevice(PeripheralSlot.SCALE);
            PeripheralSafeIo.stopReading(scale);
            scale.startContinuousReading(scaleListener);
            // RFID só liga na fase de tags — evita interferência no peso.
        }
        notifyPhaseStart();
    }

    public void stop() {
        running.set(false);
        cycleInProgress.set(false);
        armed.set(false);
        waitingForNext.set(false);
        awaitingTagStart.set(false);
        awaitingWeightStart.set(false);
        rfidCollecting.set(false);
        cancelTareMeasurement();
        context.clearTags();
        resetStabilizationTracking();
        stopScaleReading();
        stopRfidReading();
        sessionStore.clearSession();
        if (listener != null) {
            listener.onSessionCleared();
            listener.onStopped();
        }
    }

    public void restartSession() throws PeripheralException {
        if (!running.get()) {
            throw new PeripheralException("O fluxo não está em execução.");
        }
        if (cycleInProgress.get()) {
            throw new PeripheralException("Aguarde o ciclo atual terminar para reiniciar a sessão.");
        }
        waitingForNext.set(false);
        armed.set(false);
        cycleInProgress.set(false);
        rfidCollecting.set(false);
        cancelTareMeasurement();
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
        }
        resetPhaseFlags();
        notifyPhaseStart();
    }

    public WorkflowSessionStore getSessionStore() {
        return sessionStore;
    }

    @Override
    public void confirmTagReadingStart() {
        if (!running.get() || !isRfidEnabled()) {
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
        if (!running.get() || taring.get()) {
            return;
        }
        if (isRfidEnabled()) {
            // Só pesa depois da fase de tags ter começado.
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
        armed.set(true);
        resetStabilizationTracking();
        String message = config.isSimulationMode()
                ? "Modo simulação — clique em Simular pesagem estável"
                : (tareKg > 0.0005
                ? "Tara ativa — coloque os produtos e aguarde estabilização (1,5 s)..."
                : "Coloque o item (se usar caixa: Definir tara só com a caixa, depois os produtos)...");
        notifyStep(WorkflowStep.WEIGHING, message);
    }

    public void acknowledgeNext() {
        if (!running.get() || !waitingForNext.compareAndSet(true, false)) {
            return;
        }
        armed.set(false);
        cycleInProgress.set(false);
        rfidCollecting.set(false);
        context.clearTags();
        resetStabilizationTracking();
        stopRfidReading();
        resetPhaseFlags();
        notifyPhaseStart();
    }

    public boolean isRunning() {
        return running.get();
    }

    public boolean isWaitingForNext() {
        return waitingForNext.get();
    }

    public boolean isSimulationMode() {
        return config != null && config.isSimulationMode();
    }

    public void simulateWeighing(WorkflowMockScenario scenario) {
        if (!running.get() || scenario == null || config == null || !config.isSimulationMode()) {
            return;
        }
        if (cycleInProgress.get() || waitingForNext.get()) {
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

    private void runSimulatedWeighing(WorkflowMockScenario scenario) {
        try {
            int requiredMs = scenario.isFastStabilization()
                    ? WorkflowConfig.FAST_SIMULATION_STABILIZATION_MS
                    : config.getStabilizationMs();
            notifyStep(WorkflowStep.WEIGHING, scenario.isFastStabilization()
                    ? "Simulando estabilização rápida..."
                    : "Simulando estabilização (1,5 s)...");
            long start = System.currentTimeMillis();
            while (running.get()) {
                long elapsed = System.currentTimeMillis() - start;
                if (elapsed >= requiredMs) {
                    break;
                }
                notifyStabilizationProgress(elapsed);
                Thread.sleep(50);
            }
            if (!running.get()) {
                return;
            }
            runCycle(scenario.getWeightKg(), true);
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
        rfidCollecting.set(false);
        stopRfidReading();
        context.clearTags();
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
            if (listener != null) {
                listener.onWeightUpdate(event);
            }
            double gross = parseWeight(event);
            lastGrossKg = gross;
            lastGrossStable = Boolean.TRUE.equals(event.getStable());
            if (taring.get() || cycleInProgress.get() || waitingForNext.get() || !armed.get()) {
                return;
            }
            double net = toNetKg(gross);
            context.updateWeight(net, lastGrossStable);
            evaluateStabilization(net, lastGrossStable);
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

        long now = System.currentTimeMillis();
        long since = stableSinceMs.get();
        if (since == 0) {
            stableSinceMs.set(now);
            notifyStabilizationProgress(0);
            return;
        }

        long elapsed = now - since;
        int requiredMs = config.getStabilizationMs();
        if (elapsed >= requiredMs) {
            if (stabilizationTriggered.compareAndSet(false, true)) {
                armed.set(false);
                resetStabilizationTracking();
                executor.submit(() -> runCycle(weight, stable));
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
        double elapsedSec = elapsedMs / 1000.0;
        double requiredSec = requiredMs / 1000.0;
        String message = String.format("Estabilizando... %.1f s / %.1f s", elapsedSec, requiredSec);
        listener.onStabilizationProgress(message);
    }

    private void resetStabilizationTracking() {
        stableSinceMs.set(0);
        stabilizationTriggered.set(false);
    }

    private void runCycle(double weightKg, boolean stable) {
        if (!running.get() || !cycleInProgress.compareAndSet(false, true)) {
            return;
        }
        rfidCollecting.set(false);
        context.beginCycle(weightKg, stable);
        try {
            if (!running.get()) {
                return;
            }
            if (config.isEnabled(WorkflowStep.CAPTURE_PHOTO)) {
                com.peripheral.camera.CameraHardware.beginExclusiveCapture();
                try {
                    notifyStep(WorkflowStep.CAPTURE_PHOTO, "Capturando foto...");
                    photoCapture().capturePhoto(
                            context,
                            sessionStore.getSessionDirectory(),
                            sessionStore.getNextPhotoIndex());
                    notifyStep(WorkflowStep.CAPTURE_PHOTO, "Foto salva: " + context.getPhotoPath());
                } catch (Throwable photoEx) {
                    // Falha de câmera não encerra o fluxo nem a janela de operação.
                    notifyStep(WorkflowStep.CAPTURE_PHOTO,
                            "Foto não capturada: " + (photoEx.getMessage() != null
                                    ? photoEx.getMessage()
                                    : photoEx.getClass().getSimpleName()));
                } finally {
                    com.peripheral.camera.CameraHardware.endExclusiveCapture();
                }
            }
            if (!running.get()) {
                return;
            }
            if (config.isEnabled(WorkflowStep.PRINT_LABEL)) {
                int labelIndex = sessionStore.getNextLabelIndex();
                notifyStep(WorkflowStep.PRINT_LABEL, "Gerando etiqueta PDF e ZPL...");
                labelPrint().generateAndPrint(
                        context, sessionStore.getSessionDirectory(), labelIndex);
                if (sessionManager.isConnected(PeripheralSlot.PRINTER)) {
                    notifyStep(WorkflowStep.PRINT_LABEL, "Etiqueta enviada à Zebra");
                } else {
                    notifyStep(WorkflowStep.PRINT_LABEL, "Etiqueta PDF/ZPL salva (impressora não conectada)");
                }
            }
            if (!running.get()) {
                return;
            }
            waitingForNext.set(true);
            if (listener != null) {
                WorkflowReadingRecord record = sessionStore.addReading(context);
                listener.onReadingRecorded(record);
                listener.onWaitingForNext();
            }
        } catch (Exception e) {
            handleCycleFailure(e.getMessage() != null ? e.getMessage() : "Erro no fluxo", e);
        }
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

    private void injectSimulatedTags(WorkflowMockScenario scenario) {
        if (scenario == null) {
            return;
        }
        notifyStep(WorkflowStep.RFID_READ, "Simulando detecção de tags...");
        for (WorkflowMockScenario.MockTag tag : scenario.getTags()) {
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
        }
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

    private double toNetKg(double grossKg) {
        return Math.max(0, grossKg - Math.max(0, tareKg));
    }

    @Override
    public double getTareKg() {
        return tareKg;
    }

    /**
     * Desliga o RF, espera estabilizar só com a caixa e grava a tara,
     * retomando a fase em que o fluxo estava.
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
    }

    private void notifyStep(WorkflowStep step, String message) {
        if (listener != null) {
            listener.onStepChanged(step, message);
        }
    }

    private PhotoCaptureService photoCapture() {
        if (photoCaptureService == null) {
            com.peripheral.camera.CameraMicroserviceClient client =
                    com.peripheral.camera.CameraMicroserviceLifecycle.getInstance().getClient();
            photoCaptureService = new PhotoCaptureService(client);
        }
        return photoCaptureService;
    }

    private LabelPrintService labelPrint() throws PeripheralException {
        if (labelPrintService == null) {
            try {
                labelPrintService = new LabelPrintService(sessionManager);
            } catch (NoClassDefFoundError | ExceptionInInitializerError e) {
                throw new PeripheralException(
                        "Bibliotecas PDF não disponíveis (pdfbox/fontbox). Execute com ./iniciar.sh no Linux.");
            }
        }
        return labelPrintService;
    }
}
