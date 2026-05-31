package com.peripheral.core;

public class PeripheralException extends Exception {

    public PeripheralException(String message) {
        super(message);
    }

    public PeripheralException(String message, Throwable cause) {
        super(message, cause);
    }
}
