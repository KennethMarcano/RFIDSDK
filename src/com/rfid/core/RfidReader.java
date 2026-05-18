package com.rfid.core;

public interface RfidReader {

    void connect(String portName) throws RfidException;

    void disconnect();

    boolean isConnected();

    void setPowerPercent(int percent) throws RfidException;

    int getPowerPercent();

    void startContinuousReading(RfidTagListener listener) throws RfidException;

    void stopContinuousReading();

    boolean isContinuousReading();

    void readOnce(int timeoutMs, RfidTagListener listener) throws RfidException;

    String getReaderInfo();

    RfidSdkType getSdkType();
}
