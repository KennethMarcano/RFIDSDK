package com.peripheral.rfid;

import com.peripheral.core.DeviceModelEntry;
import com.peripheral.core.PeripheralDataEvent;
import com.peripheral.core.PeripheralDataListener;
import com.peripheral.core.PeripheralException;
import com.peripheral.core.ReadablePeripheral;
import com.peripheral.core.RfidConfigurable;
import com.peripheral.core.SerialConnectionConfig;
import com.rfid.core.RfidException;
import com.rfid.core.RfidReaderConfig;
import com.rfid.core.RfidTagEvent;
import com.rfid.core.RfidTagListener;
import com.rfid.impl.PayneRfidReader;

public class PayneRfidAdapter implements ReadablePeripheral, RfidConfigurable {

    private final DeviceModelEntry model;
    private final SerialConnectionConfig serialConfig;
    private PayneRfidReader reader;
    private PeripheralDataListener dataListener;

    public PayneRfidAdapter(DeviceModelEntry model, SerialConnectionConfig serialConfig) {
        this.model = model;
        this.serialConfig = serialConfig != null ? serialConfig.copy() : SerialConnectionConfig.rfidDefault();
    }

    @Override
    public DeviceModelEntry getModel() {
        return model;
    }

    @Override
    public void connect(SerialConnectionConfig config) throws PeripheralException {
        disconnect();
        SerialConnectionConfig cfg = config != null ? config.copy() : serialConfig.copy();
        if (cfg.getPortName() == null || cfg.getPortName().trim().isEmpty()) {
            throw new PeripheralException("Selecione uma porta serial");
        }
        RfidReaderConfig rfidConfig = new RfidReaderConfig()
                .setBaudRate(cfg.getBaudRate())
                .setDefaultPowerPercent(50);
        reader = new PayneRfidReader(rfidConfig);
        try {
            reader.connect(cfg.getPortName().trim());
        } catch (RfidException e) {
            reader = null;
            throw new PeripheralException(
                    PayneRfidProber.formatConnectError(cfg.getPortName().trim(), e.getMessage(), true),
                    e);
        }
    }

    @Override
    public void disconnect() {
        if (reader != null) {
            reader.disconnect();
            reader = null;
        }
        dataListener = null;
    }

    @Override
    public boolean isConnected() {
        return reader != null && reader.isConnected();
    }

    @Override
    public String getDeviceInfo() {
        return reader != null ? reader.getReaderInfo() : "-";
    }

    @Override
    public void startContinuousReading(PeripheralDataListener listener) throws PeripheralException {
        ensureConnected();
        this.dataListener = listener;
        try {
            reader.startContinuousReading(createTagListener(listener));
        } catch (RfidException e) {
            throw new PeripheralException(e.getMessage(), e);
        }
    }

    @Override
    public void stopContinuousReading() {
        if (reader != null) {
            reader.stopContinuousReading();
        }
    }

    @Override
    public void readOnce(int timeoutMs, PeripheralDataListener listener) throws PeripheralException {
        ensureConnected();
        try {
            reader.readOnce(timeoutMs, createTagListener(listener));
        } catch (RfidException e) {
            throw new PeripheralException(e.getMessage(), e);
        }
    }

    @Override
    public boolean isReading() {
        return reader != null && (reader.isContinuousReading());
    }

    @Override
    public void setPowerPercent(int percent) throws PeripheralException {
        ensureConnected();
        try {
            reader.setPowerPercent(percent);
        } catch (RfidException e) {
            throw new PeripheralException(e.getMessage(), e);
        }
    }

    @Override
    public int getPowerPercent() {
        return reader != null ? reader.getPowerPercent() : 0;
    }

    @Override
    public void setAntennaIds(int[] antennaIds) throws PeripheralException {
        ensureConnected();
        try {
            reader.setAntennaIds(antennaIds);
        } catch (RfidException e) {
            throw new PeripheralException(e.getMessage(), e);
        }
    }

    @Override
    public int[] getAntennaIds() {
        return reader != null ? reader.getAntennaIds() : new int[]{0};
    }

    private void ensureConnected() throws PeripheralException {
        if (!isConnected()) {
            throw new PeripheralException("Dispositivo não conectado");
        }
    }

    private RfidTagListener createTagListener(PeripheralDataListener listener) {
        return new RfidTagListener() {
            @Override
            public void onTag(RfidTagEvent event) {
                if (listener == null || event == null) {
                    return;
                }
                listener.onData(PeripheralDataEvent.builder(model)
                        .timestampMs(event.getTimestampMs())
                        .fromRfid(event.getEpc(), event.getCode(), event.getRssi(), event.getAntenna())
                        .build());
            }

            @Override
            public void onError(Throwable error) {
                if (listener != null) {
                    listener.onError(error);
                }
            }

            @Override
            public void onReadingStateChanged(boolean reading) {
                if (listener != null) {
                    listener.onReadingStateChanged(reading);
                }
            }
        };
    }
}
