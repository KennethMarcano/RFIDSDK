package com.rfid.core;

public class RfidReaderConfig {

    private int baudRate = 115200;
    private int defaultPowerPercent = 50;
    private int readOnceTimeoutMs = 1000;

    public int getBaudRate() {
        return baudRate;
    }

    public RfidReaderConfig setBaudRate(int baudRate) {
        this.baudRate = baudRate;
        return this;
    }

    public int getDefaultPowerPercent() {
        return defaultPowerPercent;
    }

    public RfidReaderConfig setDefaultPowerPercent(int defaultPowerPercent) {
        this.defaultPowerPercent = defaultPowerPercent;
        return this;
    }

    public int getReadOnceTimeoutMs() {
        return readOnceTimeoutMs;
    }

    public RfidReaderConfig setReadOnceTimeoutMs(int readOnceTimeoutMs) {
        this.readOnceTimeoutMs = readOnceTimeoutMs;
        return this;
    }
}
