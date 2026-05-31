package com.peripheral.core;

public class PeripheralDataEvent {

    private final DeviceModelEntry source;
    private final long timestampMs;
    private final String displayText;
    private final String rawPayload;
    private final String epc;
    private final String code;
    private final int rssi;
    private final int antenna;
    private final String weight;
    private final String unit;
    private final Boolean stable;

    private PeripheralDataEvent(Builder b) {
        this.source = b.source;
        this.timestampMs = b.timestampMs;
        this.displayText = b.displayText;
        this.rawPayload = b.rawPayload;
        this.epc = b.epc;
        this.code = b.code;
        this.rssi = b.rssi;
        this.antenna = b.antenna;
        this.weight = b.weight;
        this.unit = b.unit;
        this.stable = b.stable;
    }

    public static Builder builder(DeviceModelEntry source) {
        return new Builder(source);
    }

    public DeviceModelEntry getSource() {
        return source;
    }

    public long getTimestampMs() {
        return timestampMs;
    }

    public String getDisplayText() {
        return displayText;
    }

    public String getRawPayload() {
        return rawPayload;
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

    public String getWeight() {
        return weight;
    }

    public String getUnit() {
        return unit;
    }

    public Boolean getStable() {
        return stable;
    }

    public static final class Builder {
        private final DeviceModelEntry source;
        private long timestampMs = System.currentTimeMillis();
        private String displayText = "";
        private String rawPayload = "";
        private String epc = "";
        private String code = "";
        private int rssi;
        private int antenna;
        private String weight = "";
        private String unit = "";
        private Boolean stable;

        private Builder(DeviceModelEntry source) {
            this.source = source;
        }

        public Builder timestampMs(long timestampMs) {
            this.timestampMs = timestampMs;
            return this;
        }

        public Builder displayText(String displayText) {
            this.displayText = displayText != null ? displayText : "";
            return this;
        }

        public Builder rawPayload(String rawPayload) {
            this.rawPayload = rawPayload != null ? rawPayload : "";
            return this;
        }

        public Builder epc(String epc) {
            this.epc = epc != null ? epc : "";
            return this;
        }

        public Builder code(String code) {
            this.code = code != null ? code : "";
            return this;
        }

        public Builder rssi(int rssi) {
            this.rssi = rssi;
            return this;
        }

        public Builder antenna(int antenna) {
            this.antenna = antenna;
            return this;
        }

        public Builder weight(String weight) {
            this.weight = weight != null ? weight : "";
            return this;
        }

        public Builder unit(String unit) {
            this.unit = unit != null ? unit : "";
            return this;
        }

        public Builder stable(Boolean stable) {
            this.stable = stable;
            return this;
        }

        public Builder fromRfid(String epc, String code, int rssi, int antenna) {
            this.epc = epc != null ? epc : "";
            this.code = code != null ? code : "";
            this.rssi = rssi;
            this.antenna = antenna;
            this.displayText = "Tag: " + code + " | EPC: " + epc + " | Ant: " + antenna;
            return this;
        }

        public Builder fromRawSerial(String raw) {
            this.rawPayload = raw != null ? raw : "";
            this.displayText = raw != null ? raw.trim() : "";
            return this;
        }

        public PeripheralDataEvent build() {
            return new PeripheralDataEvent(this);
        }
    }
}
