package com.peripheral.core;

import com.peripheral.scale.DigitronScalePeripheral;
import com.peripheral.scale.Hx711ScalePeripheral;

public enum ScaleDeviceModel implements DeviceModelEntry {
    DIGITRON_RS232(
            "Digitron",
            "Balança serial",
            SdkType.DIGITRON_SERIAL,
            SerialConnectionConfig.scaleDefault()
    ),
    /** Fabricante próprio — HX711 em GPIO BCM 5 (DT) / 6 (SCK). */
    PROPIO_HX711(
            "Propio",
            "Propio",
            SdkType.HX711_GPIO,
            SerialConnectionConfig.hx711GpioDefault()
    );

    private final String vendorName;
    private final String modelName;
    private final SdkType sdk;
    private final SerialConnectionConfig defaultSerial;

    ScaleDeviceModel(String vendorName, String modelName, SdkType sdk, SerialConnectionConfig defaultSerial) {
        this.vendorName = vendorName;
        this.modelName = modelName;
        this.sdk = sdk;
        this.defaultSerial = defaultSerial;
    }

    @Override
    public PeripheralType getPeripheralType() {
        return PeripheralType.SCALE;
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
        switch (this) {
            case DIGITRON_RS232:
                return new DigitronScalePeripheral(this, serial);
            case PROPIO_HX711:
                return new Hx711ScalePeripheral(this, serial);
            default:
                throw new IllegalStateException("Modelo de balança não suportado: " + name());
        }
    }
}
