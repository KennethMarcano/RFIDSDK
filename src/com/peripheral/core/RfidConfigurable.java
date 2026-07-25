package com.peripheral.core;

public interface RfidConfigurable {

    void setPowerPercent(int percent) throws PeripheralException;

    int getPowerPercent();

    /** Potência aplicada em dBm (Mercury) ou estimativa; NaN se desconhecido. */
    double getAppliedPowerDbm();

    /** Potência máxima do módulo em dBm; NaN se desconhecido. */
    double getMaxPowerDbm();

    /** Texto de diagnóstico RF para logs/UI. */
    String getRfDiagnostics();

    void setAntennaIds(int[] antennaIds) throws PeripheralException;

    int[] getAntennaIds();
}
