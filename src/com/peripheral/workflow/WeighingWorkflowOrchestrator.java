package com.peripheral.workflow;

import com.peripheral.core.PeripheralDataEvent;
import com.peripheral.core.PeripheralDataListener;
import com.peripheral.core.PeripheralException;
import com.peripheral.core.ReadablePeripheral;
import com.peripheral.scale.DigitronDgnParser;
import com.peripheral.session.PeripheralSessionManager;
import com.peripheral.session.PeripheralSlot;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class WeighingWorkflowOrchestrator {

    private final PeripheralSessionManager sessionManager;
    private final PhotoCaptureService photoCaptureService = new PhotoCaptureService();
    private final LabelPrintService labelPrintService = new LabelPrintService();
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
    private final AtomicBoolean armed = new AtomicBoolean(true);
    private final AtomicBoolean waitingForNext = new AtomicBoolean(false);

    public WeighingWorkflowOrchestrator(PeripheralSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    public void start(WorkflowConfig config, WorkflowListener listener) throws PeripheralException {
        if (!sessionManager.isConnected(PeripheralSlot.SCALE)) {
            throw new PeripheralException("Conecte a balança antes de iniciar o fluxo");
        }
        if (config.isEnabled(WorkflowStep.RFID_READ) && !sessionManager.isConnected(PeripheralSlot.RFID_READER)) {
            throw new PeripheralException("Conecte o leitor RFID ou desabilite a leitura RFID no fluxo");
        }
        this.config = config;
        this.listener = listener;
        running.set(true);
        armed.set(true);
        waitingForNext.set(false);
        cycleInProgress.set(false);

        ReadablePeripheral scale = sessionManager.getDevice(PeripheralSlot.SCALE);
        scale.startContinuousReading(scaleListener);
        notifyStep(WorkflowStep.WEIGHING, "Aguardando peso estável maior que zero...");
    }

    public void stop() {
        running.set(false);
        cycleInProgress.set(false);
        armed.set(false);
        waitingForNext.set(false);
        stopScaleReading();
        stopRfidReading();
        if (listener != null) {
            listener.onStopped();
        }
    }

    public void acknowledgeNext() {
        if (!running.get()) {
            return;
        }
        waitingForNext.set(false);
        armed.set(true);
        cycleInProgress.set(false);
        notifyStep(WorkflowStep.WEIGHING, "Aguardando peso estável maior que zero...");
    }

    public boolean isRunning() {
        return running.get();
    }

    public boolean isWaitingForNext() {
        return waitingForNext.get();
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
            if (cycleInProgress.get() || waitingForNext.get() || !armed.get()) {
                return;
            }
            double weight = parseWeight(event);
            boolean stable = Boolean.TRUE.equals(event.getStable());
            context.updateWeight(weight, stable);
            if (stable && weight > WorkflowConfig.MIN_WEIGHT_KG) {
                executor.submit(() -> runCycle(weight, stable));
            }
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

    private void runCycle(double weightKg, boolean stable) {
        if (!running.get() || !cycleInProgress.compareAndSet(false, true)) {
            return;
        }
        armed.set(false);
        context.beginCycle(weightKg, stable);
        try {
            if (config.isEnabled(WorkflowStep.RFID_READ)) {
                runRfidBurst();
            }
            if (!running.get()) {
                return;
            }
            if (config.isEnabled(WorkflowStep.CAPTURE_PHOTO)) {
                notifyStep(WorkflowStep.CAPTURE_PHOTO, "Capturando foto...");
                photoCaptureService.capturePhoto(context);
            }
            if (!running.get()) {
                return;
            }
            if (config.isEnabled(WorkflowStep.PRINT_LABEL)) {
                notifyStep(WorkflowStep.PRINT_LABEL, "Imprimindo etiqueta...");
                labelPrintService.printLabel(context);
            }
            if (!running.get()) {
                return;
            }
            waitingForNext.set(true);
            if (listener != null) {
                listener.onCycleCompleted(context);
                listener.onWaitingForNext();
            }
        } catch (Exception e) {
            cycleInProgress.set(false);
            armed.set(true);
            if (listener != null) {
                listener.onError(e.getMessage() != null ? e.getMessage() : "Erro no fluxo", e);
            }
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
            rfid.stopContinuousReading();
        }
    }

    private void stopScaleReading() {
        ReadablePeripheral scale = sessionManager.getDevice(PeripheralSlot.SCALE);
        if (scale != null && scale.isConnected()) {
            scale.stopContinuousReading();
        }
    }

    private void stopRfidReading() {
        ReadablePeripheral rfid = sessionManager.getDevice(PeripheralSlot.RFID_READER);
        if (rfid != null && rfid.isConnected()) {
            rfid.stopContinuousReading();
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
}
