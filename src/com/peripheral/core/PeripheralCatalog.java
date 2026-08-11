package com.peripheral.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class PeripheralCatalog {

    private PeripheralCatalog() {
    }

    public static DeviceModelEntry[] modelsFor(PeripheralType type) {
        if (type == null) {
            return new DeviceModelEntry[0];
        }
        switch (type) {
            case RFID_READER:
                return RfidDeviceModel.values();
            case SCALE:
                return ScaleDeviceModel.values();
            case PRINTER:
                return PrinterDeviceModel.values();
            default:
                return new DeviceModelEntry[0];
        }
    }

    public static List<String> vendorNamesFor(PeripheralType type) {
        Set<String> vendors = new LinkedHashSet<>();
        for (DeviceModelEntry model : modelsFor(type)) {
            vendors.add(model.getVendorName());
        }
        return new ArrayList<>(vendors);
    }

    public static List<DeviceModelEntry> modelsForVendor(PeripheralType type, String vendorName) {
        List<DeviceModelEntry> result = new ArrayList<>();
        if (vendorName == null || vendorName.trim().isEmpty()) {
            return result;
        }
        for (DeviceModelEntry model : modelsFor(type)) {
            if (vendorName.equals(model.getVendorName())) {
                result.add(model);
            }
        }
        return result;
    }

    public static DeviceModelEntry find(PeripheralType type, String vendorName, String modelName) {
        for (DeviceModelEntry model : modelsFor(type)) {
            if (model.getVendorName().equals(vendorName) && model.getModelName().equals(modelName)) {
                return model;
            }
        }
        return null;
    }

    public static List<DeviceModelEntry> allModels() {
        List<DeviceModelEntry> all = new ArrayList<>();
        Collections.addAll(all, RfidDeviceModel.values());
        Collections.addAll(all, ScaleDeviceModel.values());
        Collections.addAll(all, PrinterDeviceModel.values());
        return all;
    }
}
