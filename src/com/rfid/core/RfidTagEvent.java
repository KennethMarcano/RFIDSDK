package com.rfid.core;

public class RfidTagEvent {

    private final String epc;
    private final String code;
    private final int rssi;
    private final int antenna;
    private final long timestampMs;

    public RfidTagEvent(String epc, String code, int rssi, int antenna, long timestampMs) {
        this.epc = epc != null ? epc : "";
        this.code = code != null ? code : "";
        this.rssi = rssi;
        this.antenna = antenna;
        this.timestampMs = timestampMs;
    }

    public String getEpc() {
        return epc;
    }

    public String getCode() {
        return code;
    }

    public int getRssi() {
        return rssi;
    }

    public int getAntenna() {
        return antenna;
    }

    public long getTimestampMs() {
        return timestampMs;
    }

    @Override
    public String toString() {
        return "RfidTagEvent{code='" + code + "', epc='" + epc + "', rssi=" + rssi + ", antenna=" + antenna + "}";
    }
}
