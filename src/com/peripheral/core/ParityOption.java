package com.peripheral.core;

import com.fazecast.jSerialComm.SerialPort;

public enum ParityOption {
    NONE("Nenhuma", SerialPort.NO_PARITY),
    ODD("Ímpar", SerialPort.ODD_PARITY),
    EVEN("Par", SerialPort.EVEN_PARITY);

    private final String label;
    private final int jSerialCommValue;

    ParityOption(String label, int jSerialCommValue) {
        this.label = label;
        this.jSerialCommValue = jSerialCommValue;
    }

    public String getLabel() {
        return label;
    }

    public int getJSerialCommValue() {
        return jSerialCommValue;
    }

    @Override
    public String toString() {
        return label;
    }
}
