package com.peripheral.core;

public interface ReadablePeripheral {

    DeviceModelEntry getModel();

    void connect(SerialConnectionConfig config) throws PeripheralException;

    void disconnect();

    boolean isConnected();

    String getDeviceInfo();

    void startContinuousReading(PeripheralDataListener listener) throws PeripheralException;

    void stopContinuousReading();

    void readOnce(int timeoutMs, PeripheralDataListener listener) throws PeripheralException;

    boolean isReading();
}
