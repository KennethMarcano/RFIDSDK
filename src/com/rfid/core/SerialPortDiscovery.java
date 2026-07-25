package com.rfid.core;

import com.fazecast.jSerialComm.SerialPort;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Lista portas seriais candidatas a balança / RFID.
 * Filtra dispositivos HID (touch, teclado, mouse), armazenamento e outros
 * que o jSerialComm às vezes expõe mas não servem para conexão serial de periféricos.
 */
public final class SerialPortDiscovery {

    /**
     * Palavras que indicam dispositivo NÃO serial de periférico (balança/RFID).
     * Comparação case-insensitive no nome/descrição/fabricante.
     */
    private static final String[] EXCLUDED_KEYWORDS = {
            "keyboard", "teclado",
            "mouse", "ratón", "raton",
            "hid-compliant", "hid compliant", " hid ",
            "touchscreen", "touch screen", "touch-screen", "touch panel", "touchpanel",
            "touch digitizer", "digitizer", "goodix", "ft5", "ili21",
            "mass storage", "storage", "disk drive", "pendrive", "flash drive", "removable disk",
            "bluetooth", "bthmodem",
            "webcam", "camera", "audio", "headset", "microphone",
            "printer", "scanner",
            "wireless receiver", "unifying",
            "usb input device", "input device",
            "human interface", "hid class"
    };

    /** VIDs típicos de chips USB-UART usados por balanças/RFID/conversores. */
    private static final int[] PREFERRED_SERIAL_VIDS = {
            0x0403, // FTDI
            0x10C4, // Silicon Labs CP210x
            0x1A86, // QinHeng CH340/CH341
            0x067B, // Prolific PL2303
            0x04D8, // Microchip
            0x2341, // Arduino
            0x2A03, // Arduino.org
            0x1B4F, // SparkFun
            0x26AC, // ThingMagic / related OEM (quando exposto)
            0x0FE6, // ICS Advent / some USB-serial
            0x0557, // ATEN / Prolific clones
            0x9710, // MosChip
            0x06CD, // Keyspan
            0x04B4, // Cypress (alguns bridges)
            0x1D50  // OpenMoko / Open hardware serial
    };

    private SerialPortDiscovery() {
    }

    /** Portas candidatas a conexão serial de balança/RFID (filtro ativo). */
    public static List<SerialPortInfo> listPorts() {
        return listPorts(true);
    }

    /**
     * @param onlySerialPeripherals se true, oculta touch/HID/teclado/mouse/storage/etc.
     */
    public static List<SerialPortInfo> listPorts(boolean onlySerialPeripherals) {
        try {
            SerialPort[] ports = SerialPort.getCommPorts();
            List<SerialPortInfo> result = new ArrayList<>();
            for (SerialPort port : ports) {
                SerialPortInfo info = SerialPortInfo.from(port);
                if (info.getSystemPortName().isEmpty() || info.isPlaceholder()) {
                    continue;
                }
                if (onlySerialPeripherals && !isLikelySerialPeripheral(info)) {
                    continue;
                }
                result.add(info);
            }
            result.sort(Comparator.comparing(SerialPortInfo::getSystemPortName, String.CASE_INSENSITIVE_ORDER));
            return result;
        } catch (UnsatisfiedLinkError e) {
            throw new SerialPortDiscoveryException(
                    "Biblioteca nativa jSerialComm não carregou. "
                            + "Use jSerialComm 2.11.4+ com Java 25 no Windows, ou JDK 21 LTS.",
                    e);
        }
    }

    public static List<String> listPortNames() {
        List<SerialPortInfo> ports = listPorts();
        List<String> names = new ArrayList<>();
        for (SerialPortInfo port : ports) {
            names.add(port.getSystemPortName());
        }
        return names;
    }

    /**
     * Heurística: exclui HID/touch/storage; em Windows exige perfil de conversor USB-serial
     * ou descrição clara de UART; em Linux mantém ttyUSB/ttyACM típicos.
     */
    public static boolean isLikelySerialPeripheral(SerialPortInfo info) {
        if (info == null || info.isPlaceholder()) {
            return false;
        }

        String name = info.getSystemPortName().toLowerCase(Locale.ROOT);
        String haystack = buildHaystack(info);

        for (String keyword : EXCLUDED_KEYWORDS) {
            if (haystack.contains(keyword)) {
                return false;
            }
        }

        // Portas virtuais / console que não são USB-serial de periférico
        if (name.contains("ttyprintk")
                || name.contains("com0com")
                || name.contains("cnca")
                || name.contains("cncb")) {
            return false;
        }

        if (isPreferredSerialVid(info.getVendorId())) {
            return true;
        }

        if (looksLikeUsbSerialConverter(haystack)) {
            return true;
        }

        // Linux: ttyUSB* / ttyACM* (e UART embarcada ttyAMA) sem indício de HID/touch
        if (isLinuxSerialCandidateName(name)) {
            return !looksLikeHidOrTouch(haystack);
        }

        return false;
    }

    private static boolean isLinuxSerialCandidateName(String name) {
        return name.startsWith("/dev/ttyusb")
                || name.startsWith("ttyusb")
                || name.startsWith("/dev/ttyacm")
                || name.startsWith("ttyacm")
                || name.startsWith("/dev/ttyama")
                || name.startsWith("ttyama")
                || name.startsWith("/dev/serial")
                || name.startsWith("serial/");
    }

    private static String buildHaystack(SerialPortInfo info) {
        return (info.getSystemPortName() + " "
                + info.getDescriptiveName() + " "
                + info.getDescription() + " "
                + info.getManufacturer()).toLowerCase(Locale.ROOT);
    }

    private static boolean looksLikeHidOrTouch(String haystack) {
        return haystack.contains("hid")
                || haystack.contains("touch")
                || haystack.contains("keyboard")
                || haystack.contains("mouse")
                || haystack.contains("teclado");
    }

    private static boolean looksLikeUsbSerialConverter(String haystack) {
        return haystack.contains("serial")
                || haystack.contains("uart")
                || haystack.contains("usb-serial")
                || haystack.contains("usb serial")
                || haystack.contains("ch340")
                || haystack.contains("ch341")
                || haystack.contains("cp210")
                || haystack.contains("ft232")
                || haystack.contains("ft231")
                || haystack.contains("ftdi")
                || haystack.contains("pl2303")
                || haystack.contains("prolific")
                || haystack.contains("silicon labs")
                || haystack.contains("qinheng")
                || haystack.contains("mercury")
                || haystack.contains("thingmagic")
                || haystack.contains("rfid")
                || haystack.contains("scale")
                || haystack.contains("balança")
                || haystack.contains("balanca")
                || haystack.contains("digitron")
                || haystack.contains("cdc acm")
                || haystack.contains("communications port")
                || haystack.contains("usb to serial");
    }

    private static boolean isPreferredSerialVid(int vendorId) {
        if (vendorId == 0) {
            return false;
        }
        for (int vid : PREFERRED_SERIAL_VIDS) {
            if (vid == vendorId) {
                return true;
            }
        }
        return false;
    }

    public static class SerialPortDiscoveryException extends RuntimeException {
        public SerialPortDiscoveryException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
