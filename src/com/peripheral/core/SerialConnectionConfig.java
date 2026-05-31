package com.peripheral.core;

import com.fazecast.jSerialComm.SerialPort;

public class SerialConnectionConfig {

    private String portName = "";
    private int baudRate = 115200;
    private int dataBits = 8;
    private int stopBits = 1;
    private ParityOption parity = ParityOption.NONE;

    public SerialConnectionConfig() {
    }

    public SerialConnectionConfig(String portName, int baudRate, int dataBits, int stopBits, ParityOption parity) {
        this.portName = portName != null ? portName : "";
        this.baudRate = baudRate;
        this.dataBits = dataBits;
        this.stopBits = stopBits;
        this.parity = parity != null ? parity : ParityOption.NONE;
    }

    public static SerialConnectionConfig rfidDefault() {
        return new SerialConnectionConfig("", 115200, 8, 1, ParityOption.NONE);
    }

    public static SerialConnectionConfig scaleDefault() {
        return new SerialConnectionConfig("", 9600, 8, 1, ParityOption.NONE);
    }

    public String getPortName() {
        return portName;
    }

    public SerialConnectionConfig setPortName(String portName) {
        this.portName = portName != null ? portName : "";
        return this;
    }

    public int getBaudRate() {
        return baudRate;
    }

    public SerialConnectionConfig setBaudRate(int baudRate) {
        this.baudRate = baudRate;
        return this;
    }

    public int getDataBits() {
        return dataBits;
    }

    public SerialConnectionConfig setDataBits(int dataBits) {
        this.dataBits = dataBits;
        return this;
    }

    public int getStopBits() {
        return stopBits;
    }

    public SerialConnectionConfig setStopBits(int stopBits) {
        this.stopBits = stopBits;
        return this;
    }

    public ParityOption getParity() {
        return parity;
    }

    public SerialConnectionConfig setParity(ParityOption parity) {
        this.parity = parity != null ? parity : ParityOption.NONE;
        return this;
    }

    public SerialConnectionConfig copy() {
        return new SerialConnectionConfig(portName, baudRate, dataBits, stopBits, parity);
    }

    public int getJSerialCommStopBits() {
        if (stopBits == 2) {
            return SerialPort.TWO_STOP_BITS;
        }
        return SerialPort.ONE_STOP_BIT;
    }
}
