package com.rfid.impl;

import com.rfid.core.AbstractRfidReader;
import com.rfid.core.RfidException;
import com.rfid.core.RfidReaderConfig;
import com.rfid.core.RfidSdkType;
import com.rfid.core.RfidTagEvent;
import com.rfid.core.RfidTagListener;
import com.rfid.util.MercuryUriBuilder;
import com.rfid.util.TagCodeExtractor;
import com.thingmagic.ReadExceptionListener;
import com.thingmagic.ReadListener;
import com.thingmagic.Reader;
import com.thingmagic.ReaderException;
import com.thingmagic.SimpleReadPlan;
import com.thingmagic.TMConstants;
import com.thingmagic.TagProtocol;
import com.thingmagic.TagReadData;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MercuryRfidReader extends AbstractRfidReader {

    private Reader reader;
    private String portName;
    private volatile boolean connected;
    private volatile boolean continuousReading;
    private volatile RfidTagListener tagListener;
    private int powerMinCentidBm = 500;
    private int powerMaxCentidBm = 3000;
    private volatile int nativePowerCentidBm;
    private String readerInfo = "";
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MercuryRfidReader");
        t.setDaemon(true);
        return t;
    });

    private final ReadListener mercuryReadListener = (r, tagRead) -> {
        if (tagRead == null) {
            return;
        }
        dispatchMercuryTag(tagRead);
    };

    private final ReadExceptionListener mercuryExceptionListener = (r, re) -> {
        RfidTagListener l = tagListener;
        if (l != null && re != null) {
            l.onError(re);
        }
    };

    public MercuryRfidReader(RfidReaderConfig config) {
        super(config);
    }

    @Override
    public RfidSdkType getSdkType() {
        return RfidSdkType.MERCURY;
    }

    @Override
    public void connect(String portName) throws RfidException {
        if (connected) {
            disconnect();
        }
        this.portName = portName;
        String uri = MercuryUriBuilder.fromPortName(portName);
        try {
            reader = Reader.create(uri);
            reader.connect();
            configureRegion();
            loadPowerRange();
            configureReadPlan();
            readerInfo = safeString((String) reader.paramGet("/reader/version/model"));
            if (readerInfo.isEmpty()) {
                readerInfo = "Mercury";
            }
            connected = true;
            setPowerPercent(config.getDefaultPowerPercent());
        } catch (ReaderException e) {
            destroyReaderQuietly();
            throw new RfidException("Falha ao conectar Mercury na porta " + portName + ": " + e.getMessage(), e);
        } catch (Exception e) {
            destroyReaderQuietly();
            throw new RfidException("Falha ao conectar Mercury: " + e.getMessage(), e);
        }
    }

    @Override
    public void disconnect() {
        stopContinuousReading();
        destroyReaderQuietly();
        connected = false;
        portName = null;
    }

    @Override
    public boolean isConnected() {
        return connected && reader != null;
    }

    @Override
    protected void applyNativePower(int nativePowerValue) throws RfidException {
        if (reader == null) {
            throw new RfidException("Leitor não conectado");
        }
        nativePowerCentidBm = clamp(nativePowerValue, powerMinCentidBm, powerMaxCentidBm);
        try {
            reader.paramSet(TMConstants.TMR_PARAM_RADIO_READPOWER, nativePowerCentidBm);
        } catch (ReaderException e) {
            throw new RfidException("Erro ao definir potência Mercury: " + e.getMessage(), e);
        }
    }

    @Override
    protected int percentToNative(int percent) {
        if (powerMaxCentidBm <= powerMinCentidBm) {
            return powerMinCentidBm;
        }
        double t = (percent - 1) / 99.0;
        int value = (int) Math.round(powerMinCentidBm + t * (powerMaxCentidBm - powerMinCentidBm));
        return clamp(value, powerMinCentidBm, powerMaxCentidBm);
    }

    @Override
    protected int nativeToPercent(int nativePowerValue) {
        if (powerMaxCentidBm <= powerMinCentidBm) {
            return 50;
        }
        int clamped = clamp(nativePowerValue, powerMinCentidBm, powerMaxCentidBm);
        double t = (clamped - powerMinCentidBm) / (double) (powerMaxCentidBm - powerMinCentidBm);
        return clamp((int) Math.round(1 + t * 99), 1, 100);
    }

    @Override
    public void startContinuousReading(RfidTagListener listener) throws RfidException {
        if (!isConnected()) {
            throw new RfidException("Leitor não conectado");
        }
        if (continuousReading) {
            return;
        }
        tagListener = listener;
        try {
            reader.addReadExceptionListener(mercuryExceptionListener);
            reader.addReadListener(mercuryReadListener);
            reader.startReading();
            continuousReading = true;
            notifyReadingState(true);
        } catch (Exception e) {
            throw new RfidException("Erro ao iniciar leitura contínua: " + e.getMessage(), e);
        }
    }

    @Override
    public void stopContinuousReading() {
        if (!continuousReading) {
            return;
        }
        continuousReading = false;
        if (reader != null) {
            try {
                reader.stopReading();
            } catch (Throwable ignored) {
            }
            try {
                reader.removeReadListener(mercuryReadListener);
            } catch (Throwable ignored) {
            }
            try {
                reader.removeReadExceptionListener(mercuryExceptionListener);
            } catch (Throwable ignored) {
            }
        }
        notifyReadingState(false);
    }

    @Override
    public boolean isContinuousReading() {
        return continuousReading;
    }

    @Override
    public void readOnce(int timeoutMs, RfidTagListener listener) throws RfidException {
        if (!isConnected()) {
            throw new RfidException("Leitor não conectado");
        }
        if (continuousReading) {
            throw new RfidException("Pare a leitura automática antes da leitura manual");
        }
        int ms = Math.max(200, timeoutMs);
        executor.execute(() -> {
            try {
                TagReadData[] tags = reader.read(ms);
                if (tags == null || tags.length == 0) {
                    return;
                }
                for (TagReadData tag : tags) {
                    RfidTagEvent event = toEvent(tag);
                    if (!"0".equals(event.getCode().trim())) {
                        listener.onTag(event);
                    }
                }
            } catch (Exception e) {
                listener.onError(e);
            }
        });
    }

    @Override
    public String getReaderInfo() {
        return readerInfo;
    }

    private void configureRegion() throws ReaderException {
        if (Reader.Region.UNSPEC == (Reader.Region) reader.paramGet("/reader/region/id")) {
            Reader.Region[] supported = (Reader.Region[]) reader.paramGet(TMConstants.TMR_PARAM_REGION_SUPPORTEDREGIONS);
            if (supported != null && supported.length > 0) {
                reader.paramSet("/reader/region/id", supported[0]);
            }
        }
    }

    private void loadPowerRange() throws ReaderException {
        Object minObj = reader.paramGet(TMConstants.TMR_PARAM_RADIO_POWERMIN);
        Object maxObj = reader.paramGet(TMConstants.TMR_PARAM_RADIO_POWERMAX);
        if (minObj instanceof Integer) {
            powerMinCentidBm = (Integer) minObj;
        }
        if (maxObj instanceof Integer) {
            powerMaxCentidBm = (Integer) maxObj;
        }
        if (powerMaxCentidBm < powerMinCentidBm) {
            powerMaxCentidBm = powerMinCentidBm;
        }
    }

    private void configureReadPlan() throws ReaderException {
        int[] antennaList = {1};
        SimpleReadPlan plan = new SimpleReadPlan(antennaList, TagProtocol.GEN2, null, null, 1000);
        reader.paramSet(TMConstants.TMR_PARAM_READ_PLAN, plan);
    }

    private void dispatchMercuryTag(TagReadData tagRead) {
        RfidTagListener l = tagListener;
        if (l == null) {
            return;
        }
        RfidTagEvent event = toEvent(tagRead);
        if (!"0".equals(event.getCode().trim())) {
            l.onTag(event);
        }
    }

    private static RfidTagEvent toEvent(TagReadData tagRead) {
        String epc = tagRead.epcString();
        String code = TagCodeExtractor.fromEpcHex(epc);
        int rssi = 0;
        int antenna = 1;
        try {
            rssi = tagRead.getRssi();
        } catch (Throwable ignored) {
        }
        try {
            antenna = tagRead.getAntenna();
        } catch (Throwable ignored) {
        }
        return new RfidTagEvent(epc, code, rssi, antenna, System.currentTimeMillis());
    }

    private void destroyReaderQuietly() {
        if (reader != null) {
            try {
                reader.destroy();
            } catch (Throwable ignored) {
            }
            reader = null;
        }
    }

    private void notifyReadingState(boolean reading) {
        RfidTagListener l = tagListener;
        if (l != null) {
            l.onReadingStateChanged(reading);
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String safeString(String s) {
        return s != null ? s : "";
    }
}
