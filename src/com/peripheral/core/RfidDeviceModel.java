package com.peripheral.core;

import com.peripheral.rfid.MercuryRfidAdapter;
import com.peripheral.rfid.PayneRfidAdapter;

public enum RfidDeviceModel implements DeviceModelEntry {
    /** Padrão do dispositivo de campo. */
    MERCURY_M6E(
            "ThingMagic",
            "Mercury API",
            SdkType.THINGMAGIC_MERCURY,
            SerialConnectionConfig.rfidDefault()
    ),
    PAYNE_UHF(
            "Payne",
            "Módulo UHF",
            SdkType.PAYNE,
            SerialConnectionConfig.rfidDefault()
    );

    private final String vendorName;
    private final String modelName;
    private final SdkType sdk;
    private final SerialConnectionConfig defaultSerial;

    RfidDeviceModel(String vendorName, String modelName, SdkType sdk, SerialConnectionConfig defaultSerial) {
        this.vendorName = vendorName;
        this.modelName = modelName;
        this.sdk = sdk;
        this.defaultSerial = defaultSerial;
    }

    @Override
    public PeripheralType getPeripheralType() {
        return PeripheralType.RFID_READER;
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
            case PAYNE_UHF:
                return new PayneRfidAdapter(this, serial);
            case MERCURY_M6E:
                return new MercuryRfidAdapter(this, serial);
            default:
                throw new IllegalStateException("Modelo RFID não suportado: " + name());
        }
    }
}
