package com.peripheral.core;

public interface RfidConfigurable {

    void setPowerPercent(int percent) throws PeripheralException;

    int getPowerPercent();

    void setAntennaIds(int[] antennaIds) throws PeripheralException;

    int[] getAntennaIds();
}
