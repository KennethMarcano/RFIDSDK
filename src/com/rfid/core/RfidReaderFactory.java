package com.rfid.core;

import com.rfid.impl.MercuryRfidReader;
import com.rfid.impl.PayneRfidReader;

public final class RfidReaderFactory {

    private RfidReaderFactory() {
    }

    public static RfidReader create(RfidSdkType sdkType, RfidReaderConfig config) {
        if (sdkType == null) {
            throw new IllegalArgumentException("SDK não selecionado");
        }
        RfidReaderConfig cfg = config != null ? config : new RfidReaderConfig();
        switch (sdkType) {
            case PAYNE:
                return new PayneRfidReader(cfg);
            case MERCURY:
                return new MercuryRfidReader(cfg);
            default:
                throw new IllegalArgumentException("SDK não suportado: " + sdkType);
        }
    }
}
