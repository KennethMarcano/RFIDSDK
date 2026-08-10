package com.peripheral.core;

public enum SdkType {
    PAYNE("Payne Reader SDK", false, "libs/lib_reader.jar", true),
    THINGMAGIC_MERCURY("ThingMagic Mercury API", true, "SDKMERCURY (java.library.path)", true),
    DIGITRON_SERIAL("Digitron serial protocol", false, "jSerialComm", true),
    HX711_GPIO("HX711 GPIO (DT/SCK)", false, "python3 + RPi.GPIO/lgpio", false);

    private final String description;
    private final boolean requiresNativeLibrary;
    private final String libraryHint;
    private final boolean usesSerialPort;

    SdkType(String description, boolean requiresNativeLibrary, String libraryHint, boolean usesSerialPort) {
        this.description = description;
        this.requiresNativeLibrary = requiresNativeLibrary;
        this.libraryHint = libraryHint;
        this.usesSerialPort = usesSerialPort;
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

    /** false para interfaces GPIO (ex.: HX711); true para RS232/USB-serial. */
    public boolean usesSerialPort() {
        return usesSerialPort;
    }
}
