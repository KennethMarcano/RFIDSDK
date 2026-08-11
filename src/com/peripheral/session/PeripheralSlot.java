package com.peripheral.session;

import com.peripheral.core.PeripheralType;

public enum PeripheralSlot {

    SCALE(PeripheralType.SCALE, "Balança"),
    RFID_READER(PeripheralType.RFID_READER, "Leitor RFID"),
    PRINTER(PeripheralType.PRINTER, "Impressora");

    private final PeripheralType peripheralType;
    private final String label;

    PeripheralSlot(PeripheralType peripheralType, String label) {
        this.peripheralType = peripheralType;
        this.label = label;
    }

    public PeripheralType getPeripheralType() {
        return peripheralType;
    }

    public String getLabel() {
        return label;
    }
}
