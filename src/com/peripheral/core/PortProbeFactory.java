package com.peripheral.core;

import com.peripheral.printer.ZebraPrinterProber;
import com.peripheral.rfid.MercuryRfidProber;
import com.peripheral.rfid.PayneRfidProber;
import com.peripheral.scale.DigitronScaleProber;
import com.peripheral.scale.Hx711ScaleProber;

public final class PortProbeFactory {

    private PortProbeFactory() {
    }

    public static SerialPortProber forModel(DeviceModelEntry model) {
        if (model == null) {
            throw new IllegalArgumentException("Modelo não selecionado");
        }
        if (model instanceof RfidDeviceModel) {
            switch ((RfidDeviceModel) model) {
                case PAYNE_UHF:
                    return new PayneRfidProber();
                case MERCURY_M6E:
                    return new MercuryRfidProber();
                default:
                    throw new IllegalStateException("Probe RFID não definido: " + model);
            }
        }
        if (model instanceof ScaleDeviceModel) {
            switch ((ScaleDeviceModel) model) {
                case DIGITRON_RS232:
                    return new DigitronScaleProber();
                case PROPIO_HX711:
                    return new Hx711ScaleProber();
                default:
                    throw new IllegalStateException("Probe balança não definido: " + model);
            }
        }
        if (model instanceof PrinterDeviceModel) {
            return new ZebraPrinterProber();
        }
        throw new IllegalStateException("Probe não suportado para: " + model.getDisplayLabel());
    }

    public static long defaultTimeoutMs(DeviceModelEntry model) {
        if (model == null) {
            return 3000;
        }
        if (model instanceof ScaleDeviceModel
                && (ScaleDeviceModel) model == ScaleDeviceModel.PROPIO_HX711) {
            // Tara + janela filtrada do hx711_reader.py precisa de mais tempo que RS232
            return 12000;
        }
        if (model.getPeripheralType() == PeripheralType.SCALE) {
            return 4000;
        }
        if (model instanceof RfidDeviceModel && ((RfidDeviceModel) model) == RfidDeviceModel.MERCURY_M6E) {
            return 5000;
        }
        return 3000;
    }
}
