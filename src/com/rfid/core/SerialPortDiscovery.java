package com.rfid.core;

import com.fazecast.jSerialComm.SerialPort;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class SerialPortDiscovery {

    private SerialPortDiscovery() {
    }

    public static List<String> listPortNames() {
        try {
            SerialPort[] ports = SerialPort.getCommPorts();
            List<String> names = new ArrayList<>();
            for (SerialPort port : ports) {
                String name = port.getSystemPortName();
                if (name != null && !name.trim().isEmpty()) {
                    names.add(name.trim());
                }
            }
            Collections.sort(names);
            return names;
        } catch (UnsatisfiedLinkError e) {
            throw new SerialPortDiscoveryException(
                    "Biblioteca nativa jSerialComm não carregou. "
                            + "Use jSerialComm 2.11.4+ com Java 25 no Windows, ou JDK 21 LTS.",
                    e);
        }
    }

    public static class SerialPortDiscoveryException extends RuntimeException {
        public SerialPortDiscoveryException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
