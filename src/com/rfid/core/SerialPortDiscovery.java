package com.rfid.core;

import com.fazecast.jSerialComm.SerialPort;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class SerialPortDiscovery {

    private SerialPortDiscovery() {
    }

    public static List<SerialPortInfo> listPorts() {
        try {
            SerialPort[] ports = SerialPort.getCommPorts();
            List<SerialPortInfo> result = new ArrayList<>();
            for (SerialPort port : ports) {
                SerialPortInfo info = SerialPortInfo.from(port);
                if (!info.getSystemPortName().isEmpty()) {
                    result.add(info);
                }
            }
            result.sort(Comparator.comparing(SerialPortInfo::getSystemPortName, String.CASE_INSENSITIVE_ORDER));
            return result;
        } catch (UnsatisfiedLinkError e) {
            throw new SerialPortDiscoveryException(
                    "Biblioteca nativa jSerialComm não carregou. "
                            + "Use jSerialComm 2.11.4+ com Java 25 no Windows, ou JDK 21 LTS.",
                    e);
        }
    }

    public static List<String> listPortNames() {
        List<SerialPortInfo> ports = listPorts();
        List<String> names = new ArrayList<>();
        for (SerialPortInfo port : ports) {
            names.add(port.getSystemPortName());
        }
        return names;
    }

    public static class SerialPortDiscoveryException extends RuntimeException {
        public SerialPortDiscoveryException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
