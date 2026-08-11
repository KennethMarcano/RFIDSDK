package com.peripheral.core;

import com.peripheral.printer.ZebraPrinterPeripheral;

public enum PrinterDeviceModel implements DeviceModelEntry {
    ZEBRA_ZD230(
            "Zebra",
            "ZD230",
            SdkType.ZEBRA_ZPL,
            SerialConnectionConfig.printerDefault()
    );

    private final String vendorName;
    private final String modelName;
    private final SdkType sdk;
    private final SerialConnectionConfig defaultSerial;

    PrinterDeviceModel(String vendorName, String modelName, SdkType sdk, SerialConnectionConfig defaultSerial) {
        this.vendorName = vendorName;
        this.modelName = modelName;
        this.sdk = sdk;
        this.defaultSerial = defaultSerial;
    }

    @Override
    public PeripheralType getPeripheralType() {
        return PeripheralType.PRINTER;
    }

    @Override
    public String getVendorName() {
        return vendorName;
    }

    @Override
    public String getModelName() {
        return modelName;
    }

    @Override
    public SdkType getSdk() {
        return sdk;
    }

    @Override
    public SerialConnectionConfig getDefaultSerialConfig() {
        return defaultSerial.copy();
    }

    @Override
    public String getDisplayLabel() {
        return vendorName + " — " + modelName;
    }

    @Override
    public ReadablePeripheral create(SerialConnectionConfig config) {
        SerialConnectionConfig serial = config != null ? config.copy() : getDefaultSerialConfig();
        return new ZebraPrinterPeripheral(this, serial);
    }
}
