package com.peripheral.core;

public interface DeviceModelEntry {

    PeripheralType getPeripheralType();

    String getVendorName();

    String getModelName();

    SdkType getSdk();

    SerialConnectionConfig getDefaultSerialConfig();

    String getDisplayLabel();

    ReadablePeripheral create(SerialConnectionConfig config);

    /** true se a conexão exige porta serial (COM/tty); false para GPIO etc. */
    default boolean usesSerialPort() {
        SdkType sdk = getSdk();
        return sdk == null || sdk.usesSerialPort();
    }
}
