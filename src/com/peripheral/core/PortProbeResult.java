package com.peripheral.core;

public final class PortProbeResult {

    private final PortProbeStatus status;
    private final String message;
    private final String detail;

    private PortProbeResult(PortProbeStatus status, String message, String detail) {
        this.status = status;
        this.message = message != null ? message : "";
        this.detail = detail != null ? detail : "";
    }

    public static PortProbeResult match(String message, String detail) {
        return new PortProbeResult(PortProbeStatus.MATCH, message, detail);
    }

    public static PortProbeResult noResponse(String message, String detail) {
        return new PortProbeResult(PortProbeStatus.NO_RESPONSE, message, detail);
    }

    public static PortProbeResult wrongDevice(String message, String detail) {
        return new PortProbeResult(PortProbeStatus.WRONG_DEVICE, message, detail);
    }

    public static PortProbeResult openFailed(String message, String detail) {
        return new PortProbeResult(PortProbeStatus.OPEN_FAILED, message, detail);
    }

    public static PortProbeResult busy(String message, String detail) {
        return new PortProbeResult(PortProbeStatus.BUSY, message, detail);
    }

    public PortProbeStatus getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }

    public String getDetail() {
        return detail;
    }

    public boolean isMatch() {
        return status == PortProbeStatus.MATCH;
    }

    public boolean isSuspicious() {
        return status == PortProbeStatus.NO_RESPONSE || status == PortProbeStatus.WRONG_DEVICE;
    }

    public boolean isBlocking() {
        return status == PortProbeStatus.OPEN_FAILED || status == PortProbeStatus.BUSY;
    }
}
