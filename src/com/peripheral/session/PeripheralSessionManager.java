package com.peripheral.session;

import com.peripheral.core.DeviceModelEntry;
import com.peripheral.core.PeripheralException;
import com.peripheral.core.PeripheralFactory;
import com.peripheral.core.ReadablePeripheral;
import com.peripheral.core.RfidConfigurable;
import com.peripheral.core.SerialConnectionConfig;
import com.peripheral.scale.Hx711GpioPins;

import java.util.EnumMap;
import java.util.Map;

public class PeripheralSessionManager {

    private final Map<PeripheralSlot, PeripheralConnectionHandle> handles = new EnumMap<>(PeripheralSlot.class);

    public PeripheralSessionManager() {
        for (PeripheralSlot slot : PeripheralSlot.values()) {
            handles.put(slot, new PeripheralConnectionHandle(slot));
        }
    }

    public PeripheralConnectionHandle getHandle(PeripheralSlot slot) {
        return handles.get(slot);
    }

    public ReadablePeripheral getDevice(PeripheralSlot slot) {
        PeripheralConnectionHandle handle = handles.get(slot);
        return handle != null ? handle.getDevice() : null;
    }

    public boolean isConnected(PeripheralSlot slot) {
        PeripheralConnectionHandle handle = handles.get(slot);
        return handle != null && handle.isConnected();
    }

    public void connect(PeripheralSlot slot, DeviceModelEntry model, SerialConnectionConfig config,
                        int powerPercent, int[] antennaIds) throws PeripheralException {
        boolean usesSerial = model == null || model.usesSerialPort();
        String port = config != null ? config.getPortName() : null;
        if (usesSerial) {
            if (port == null || port.trim().isEmpty()) {
                throw new PeripheralException("Selecione uma porta serial válida");
            }
            String conflict = findPortConflict(slot, port.trim());
            if (conflict != null) {
                throw new PeripheralException(conflict);
            }
        } else if (config != null && (port == null || port.trim().isEmpty())) {
            config.setPortName(Hx711GpioPins.LOGICAL_PORT);
        }
        disconnect(slot);
        ReadablePeripheral device = PeripheralFactory.create(model, config);
        device.connect(config);
        if (device instanceof RfidConfigurable) {
            RfidConfigurable rfid = (RfidConfigurable) device;
            rfid.setPowerPercent(powerPercent);
            rfid.setAntennaIds(antennaIds);
        }
        PeripheralConnectionHandle handle = handles.get(slot);
        handle.setModel(model);
        handle.setSerialConfig(config);
        handle.setDevice(device);
    }

    public void disconnect(PeripheralSlot slot) {
        PeripheralConnectionHandle handle = handles.get(slot);
        if (handle != null) {
            handle.clear();
        }
    }

    public void disconnectAll() {
        for (PeripheralSlot slot : PeripheralSlot.values()) {
            disconnect(slot);
        }
    }

    public String findPortConflict(PeripheralSlot connectingSlot, String portName) {
        if (portName == null || portName.isEmpty()) {
            return null;
        }
        for (PeripheralSlot other : PeripheralSlot.values()) {
            if (other == connectingSlot) {
                continue;
            }
            PeripheralConnectionHandle handle = handles.get(other);
            if (handle != null && handle.isConnected()) {
                String otherPort = handle.getPortName();
                if (otherPort != null && otherPort.equalsIgnoreCase(portName)) {
                    return "A porta " + portName + " já está em uso pelo "
                            + other.getLabel() + ". Escolha outra porta.";
                }
            }
        }
        return null;
    }

    public String findPortConflictForSelection(PeripheralSlot slot, String portName) {
        if (portName == null || portName.isEmpty()) {
            return null;
        }
        for (PeripheralSlot other : PeripheralSlot.values()) {
            if (other == slot) {
                continue;
            }
            PeripheralConnectionHandle handle = handles.get(other);
            String otherPort = handle != null ? handle.getPortName() : null;
            if (otherPort == null) {
                continue;
            }
            if (otherPort.equalsIgnoreCase(portName)) {
                return "A porta " + portName + " já está selecionada/conectada para o "
                        + other.getLabel() + ".";
            }
        }
        return null;
    }
}
