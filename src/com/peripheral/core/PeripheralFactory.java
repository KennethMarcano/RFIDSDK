package com.peripheral.core;

public final class PeripheralFactory {

    private PeripheralFactory() {
    }

    public static ReadablePeripheral create(DeviceModelEntry model, SerialConnectionConfig serial) {
        if (model == null) {
            throw new IllegalArgumentException("Modelo não selecionado");
        }
        return model.create(serial);
    }
}
