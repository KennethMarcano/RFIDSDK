package com.peripheral.scale;

import com.peripheral.core.DeviceModelEntry;
import com.peripheral.core.PeripheralDataEvent;
import com.peripheral.core.PeripheralDataListener;
import com.peripheral.core.PeripheralException;
import com.peripheral.core.ReadablePeripheral;
import com.peripheral.core.ScaleConfigurable;
import com.peripheral.core.SerialConnectionConfig;
import com.peripheral.transport.SerialLink;

import java.util.concurrent.atomic.AtomicBoolean;

public class DigitronScalePeripheral implements ReadablePeripheral, ScaleConfigurable {

    private final DeviceModelEntry model;
    private SerialConnectionConfig serialConfig;
    private SerialLink serialLink;
    private PeripheralDataListener dataListener;
    private final AtomicBoolean continuousReading = new AtomicBoolean(false);
    private Thread readOnceThread;

    public DigitronScalePeripheral(DeviceModelEntry model, SerialConnectionConfig serialConfig) {
        this.model = model;
        this.serialConfig = serialConfig != null ? serialConfig.copy() : SerialConnectionConfig.scaleDefault();
    }

    @Override
    public DeviceModelEntry getModel() {
        return model;
    }

    @Override
    public void connect(SerialConnectionConfig config) throws PeripheralException {
        disconnect();
        if (config != null) {
            serialConfig = config.copy();
        }
        if (serialConfig.getPortName() == null || serialConfig.getPortName().trim().isEmpty()) {
            throw new PeripheralException("Selecione uma porta serial");
        }
        serialLink = new SerialLink();
        serialLink.open(serialConfig);
    }

    @Override
    public void disconnect() {
        stopContinuousReading();
        if (serialLink != null) {
            serialLink.close();
            serialLink = null;
        }
        dataListener = null;
    }

    @Override
    public boolean isConnected() {
        return serialLink != null && serialLink.isOpen();
    }

    @Override
    public String getDeviceInfo() {
        if (!isConnected()) {
            return "-";
        }
        return "Digitron | " + serialConfig.getPortName()
                + " @ " + serialConfig.getBaudRate()
                + " " + serialConfig.getDataBits()
                + serialConfig.getParity().getLabel().charAt(0)
                + serialConfig.getStopBits();
    }

    @Override
    public void startContinuousReading(PeripheralDataListener listener) throws PeripheralException {
        ensureConnected();
        if (continuousReading.get()) {
            return;
        }
        dataListener = listener;
        continuousReading.set(true);
        serialLink.setLineListener(line -> dispatchRaw(line, listener));
        notifyReadingState(listener, true);
    }

    @Override
    public void stopContinuousReading() {
        if (!continuousReading.getAndSet(false)) {
            return;
        }
        if (serialLink != null) {
            serialLink.setLineListener(null);
        }
        if (readOnceThread != null && readOnceThread.isAlive()) {
            readOnceThread.interrupt();
            readOnceThread = null;
        }
        notifyReadingState(dataListener, false);
    }

    @Override
    public void readOnce(int timeoutMs, PeripheralDataListener listener) throws PeripheralException {
        ensureConnected();
        if (continuousReading.get()) {
            throw new PeripheralException("Pare a leitura contínua antes da leitura manual");
        }
        notifyReadingState(listener, true);
        readOnceThread = new Thread(() -> {
            try {
                String line = serialLink.pollLine(Math.max(200, timeoutMs));
                if (line != null && !line.isEmpty()) {
                    dispatchRaw(line, listener);
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                notifyReadingState(listener, false);
            }
        }, "digitron-read-once");
        readOnceThread.setDaemon(true);
        readOnceThread.start();
    }

    @Override
    public boolean isReading() {
        return continuousReading.get()
                || (readOnceThread != null && readOnceThread.isAlive());
    }

    @Override
    public SerialConnectionConfig getSerialConfig() {
        return serialConfig.copy();
    }

    @Override
    public void setSerialConfig(SerialConnectionConfig config) {
        if (config != null) {
            serialConfig = config.copy();
        }
    }

    private void dispatchRaw(String line, PeripheralDataListener listener) {
        if (listener == null || line == null || line.trim().isEmpty()) {
            return;
        }
        listener.onData(PeripheralDataEvent.builder(model)
                .fromRawSerial(line)
                .build());
    }

    private void notifyReadingState(PeripheralDataListener listener, boolean reading) {
        if (listener != null) {
            listener.onReadingStateChanged(reading);
        }
    }

    private void ensureConnected() throws PeripheralException {
        if (!isConnected()) {
            throw new PeripheralException("Balança não conectada");
        }
    }
}
