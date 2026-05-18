package com.rfid.core;

public interface RfidTagListener {

    void onTag(RfidTagEvent event);

    default void onError(Throwable error) {
    }

    default void onReadingStateChanged(boolean reading) {
    }
}
