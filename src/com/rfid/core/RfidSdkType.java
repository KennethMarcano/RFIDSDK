package com.rfid.core;

public enum RfidSdkType {
    PAYNE("Payne"),
    MERCURY("Mercury (ThingMagic)");

    private final String displayName;

    RfidSdkType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
