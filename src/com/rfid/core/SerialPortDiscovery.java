package com.rfid.core;

import com.fazecast.jSerialComm.SerialPort;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class SerialPortDiscovery {

    private SerialPortDiscovery() {
    }

    public static List<String> listPortNames() {
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
    }
}
