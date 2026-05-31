package com.rfid.core;

public class RfidReaderConfig {

    private int baudRate = 115200;
    private int defaultPowerPercent = 50;
    private int readOnceTimeoutMs = 1000;
    private int[] antennaIds = {0};

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

    public int[] getAntennaIds() {
        return antennaIds != null ? antennaIds.clone() : new int[]{0};
    }

    public RfidReaderConfig setAntennaIds(int[] antennaIds) {
        if (antennaIds == null || antennaIds.length == 0) {
            this.antennaIds = new int[]{0};
        } else {
            this.antennaIds = antennaIds.clone();
        }
        return this;
    }
}
