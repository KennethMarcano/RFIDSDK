package com.peripheral.core;

public interface DeviceModelEntry {

    PeripheralType getPeripheralType();

    String getVendorName();

    String getModelName();

    SdkType getSdk();

    SerialConnectionConfig getDefaultSerialConfig();

    String getDisplayLabel();

    ReadablePeripheral create(SerialConnectionConfig config);
}
