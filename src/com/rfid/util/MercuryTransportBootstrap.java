package com.rfid.util;

import com.thingmagic.Reader;
import com.thingmagic.ReaderFactory;
import com.thingmagic.SerialTransportJSerialComm;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * No Linux (incluindo aarch64/ARM64), registra transporte serial Java (jSerialComm)
 * antes de {@code Reader.create()}, evitando {@link com.thingmagic.SerialTransportNative}.
 * <p>
 * Em ARM64 o Mercury SDK procura {@code linux-aarch64.lib}, mas o JAR só inclui
 * {@code linux-arm.lib} — a inicialização JNI falha com {@code NoClassDefFoundError}.
 */
public final class MercuryTransportBootstrap {

    private static volatile boolean installed;

    private MercuryTransportBootstrap() {
    }

    public static void installIfLinux() {
        if (installed) {
            return;
        }
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("linux")) {
            return;
        }
        try {
            Field field = Reader.class.getDeclaredField("readerFactoryDispatchTable");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, ReaderFactory> table = (Map<String, ReaderFactory>) field.get(null);
            synchronized (table) {
                ReaderFactory factory = new SerialTransportJSerialComm.Factory();
                table.put("eapi", factory);
                table.put("tmr", factory);
                installed = true;
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "Não foi possível configurar transporte Mercury no Linux: " + e.getMessage(), e);
        }
    }

    /** {@code true} em Linux ARM64 ({@code aarch64}), onde o JNI Mercury não funciona. */
    public static boolean isLinuxAarch64() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("linux")) {
            return false;
        }
        String arch = System.getProperty("os.arch", "").toLowerCase();
        return "aarch64".equals(arch) || "arm64".equals(arch);
    }
}
