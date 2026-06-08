package com.rfid.util;

import com.thingmagic.Reader;
import com.thingmagic.ReaderFactory;
import com.thingmagic.SerialTransportJSerialComm;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * No Linux, registra transporte serial Java (jSerialComm) antes de {@code Reader.create()},
 * evitando carregar {@code SerialTransportNative} e sua biblioteca nativa JNI.
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
                if (table.isEmpty()) {
                    ReaderFactory factory = new SerialTransportJSerialComm.Factory();
                    table.put("eapi", factory);
                    table.put("tmr", factory);
                }
                installed = true;
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "Não foi possível configurar transporte Mercury no Linux: " + e.getMessage(), e);
        }
    }
}
