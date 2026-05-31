package com.peripheral.core;

public enum SdkType {
    PAYNE("Payne Reader SDK", false, "libs/lib_reader.jar"),
    THINGMAGIC_MERCURY("ThingMagic Mercury API", true, "SDKMERCURY (java.library.path)"),
    DIGITRON_SERIAL("Digitron serial protocol", false, "jSerialComm");

    private final String description;
    private final boolean requiresNativeLibrary;
    private final String libraryHint;

    SdkType(String description, boolean requiresNativeLibrary, String libraryHint) {
        this.description = description;
        this.requiresNativeLibrary = requiresNativeLibrary;
        this.libraryHint = libraryHint;
    }

    public String getDescription() {
        return description;
    }

    public boolean requiresNativeLibrary() {
        return requiresNativeLibrary;
    }

    public String getLibraryHint() {
        return libraryHint;
    }
}
