package com.rfid.core;

import com.fazecast.jSerialComm.SerialPort;

public final class SerialPortInfo {

    private final String systemPortName;
    private final String descriptiveName;
    private final String description;
    private final String manufacturer;
    private final int vendorId;
    private final int productId;
    private final String serialNumber;
    private final String location;

    public SerialPortInfo(
            String systemPortName,
            String descriptiveName,
            String description,
            String manufacturer,
            int vendorId,
            int productId,
            String serialNumber,
            String location) {
        this.systemPortName = safe(systemPortName);
        this.descriptiveName = safe(descriptiveName);
        this.description = safe(description);
        this.manufacturer = safe(manufacturer);
        this.vendorId = vendorId;
        this.productId = productId;
        this.serialNumber = safe(serialNumber);
        this.location = safe(location);
    }

    public static SerialPortInfo from(SerialPort port) {
        if (port == null) {
            return placeholder("(porta inválida)");
        }
        return new SerialPortInfo(
                port.getSystemPortName(),
                port.getDescriptivePortName(),
                port.getPortDescription(),
                port.getManufacturer(),
                port.getVendorID(),
                port.getProductID(),
                port.getSerialNumber(),
                port.getPortLocation()
        );
    }

    public static SerialPortInfo placeholder(String message) {
        String text = safe(message);
        return new SerialPortInfo(text, text, "", "", 0, 0, "", "");
    }

    public String getSystemPortName() {
        return systemPortName;
    }

    public String getDescriptiveName() {
        return descriptiveName;
    }

    public String getDescription() {
        return description;
    }

    public String getManufacturer() {
        return manufacturer;
    }

    public int getVendorId() {
        return vendorId;
    }

    public int getProductId() {
        return productId;
    }

    public String getSerialNumber() {
        return serialNumber;
    }

    public String getLocation() {
        return location;
    }

    public boolean isPlaceholder() {
        return systemPortName.startsWith("(");
    }

    public String getDisplayLabel() {
        if (isPlaceholder()) {
            return systemPortName;
        }
        String deviceName = pickDeviceName();
        if (deviceName.isEmpty()) {
            return systemPortName;
        }
        return systemPortName + " — " + deviceName;
    }

    public String getDetailTooltip() {
        if (isPlaceholder()) {
            return systemPortName;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Porta: ").append(systemPortName);
        if (!descriptiveName.isEmpty()) {
            sb.append("\nNome: ").append(descriptiveName);
        }
        if (!description.isEmpty()) {
            sb.append("\nDescrição: ").append(description);
        }
        if (!manufacturer.isEmpty()) {
            sb.append("\nFabricante: ").append(manufacturer);
        }
        if (vendorId != 0 || productId != 0) {
            sb.append(String.format("\nVID:PID: %04X:%04X", vendorId, productId));
        }
        if (!serialNumber.isEmpty()) {
            sb.append("\nSerial USB: ").append(serialNumber);
        }
        if (!location.isEmpty()) {
            sb.append("\nLocalização: ").append(location);
        }
        return sb.toString();
    }

    public String getStableId() {
        if (isPlaceholder()) {
            return "";
        }
        return String.format("%04X:%04X:%s", vendorId, productId, serialNumber);
    }

    @Override
    public String toString() {
        return getDisplayLabel();
    }

    private String pickDeviceName() {
        if (!descriptiveName.isEmpty() && !descriptiveName.equalsIgnoreCase(systemPortName)) {
            return descriptiveName;
        }
        if (!description.isEmpty()) {
            return description;
        }
        if (!manufacturer.isEmpty()) {
            return manufacturer;
        }
        return "";
    }

    private static String safe(String value) {
        return value != null ? value.trim() : "";
    }
}
