package com.peripheral.core;

public enum PeripheralType {
    RFID_READER("Módulo RFID"),
    SCALE("Balança");

    private final String label;

    PeripheralType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    @Override
    public String toString() {
        return label;
    }
}
