package com.peripheral.core;

public interface PeripheralDataListener {

    void onData(PeripheralDataEvent event);

    default void onError(Throwable error) {
    }

    default void onReadingStateChanged(boolean reading) {
    }
}
