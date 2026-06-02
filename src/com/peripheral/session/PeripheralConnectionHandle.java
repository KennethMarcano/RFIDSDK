package com.peripheral.session;

import com.peripheral.core.DeviceModelEntry;
import com.peripheral.core.ReadablePeripheral;
import com.peripheral.core.SerialConnectionConfig;

public class PeripheralConnectionHandle {

    private final PeripheralSlot slot;
    private DeviceModelEntry model;
    private SerialConnectionConfig serialConfig;
    private ReadablePeripheral device;

    public PeripheralConnectionHandle(PeripheralSlot slot) {
        this.slot = slot;
    }

    public PeripheralSlot getSlot() {
        return slot;
    }

    public DeviceModelEntry getModel() {
        return model;
    }

    public void setModel(DeviceModelEntry model) {
        this.model = model;
    }

    public SerialConnectionConfig getSerialConfig() {
        return serialConfig;
    }

    public void setSerialConfig(SerialConnectionConfig serialConfig) {
        this.serialConfig = serialConfig != null ? serialConfig.copy() : null;
    }

    public ReadablePeripheral getDevice() {
        return device;
    }

    public void setDevice(ReadablePeripheral device) {
        this.device = device;
    }

    public boolean isConnected() {
        return device != null && device.isConnected();
    }

    public String getPortName() {
        if (serialConfig == null || serialConfig.getPortName() == null) {
            return null;
        }
        return serialConfig.getPortName().trim();
    }

    public void clear() {
        if (device != null) {
            device.disconnect();
            device = null;
        }
        model = null;
        serialConfig = null;
    }
}
