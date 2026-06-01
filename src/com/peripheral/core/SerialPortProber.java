package com.peripheral.core;

public interface SerialPortProber {

    PortProbeResult probe(SerialConnectionConfig config, long timeoutMs);
}
