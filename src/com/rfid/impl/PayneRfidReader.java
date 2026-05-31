package com.rfid.impl;

import com.payne.reader.Reader;
import com.payne.reader.base.BaseInventory;
import com.payne.reader.base.Consumer;
import com.payne.reader.bean.config.AntennaCount;
import com.payne.reader.bean.config.Session;
import com.payne.reader.bean.config.Target;
import com.payne.reader.bean.receive.InventoryFailure;
import com.payne.reader.bean.receive.InventoryTag;
import com.payne.reader.bean.receive.InventoryTagEnd;
import com.payne.reader.bean.receive.Version;
import com.payne.reader.bean.send.CustomSessionTargetInventory;
import com.payne.reader.bean.send.InventoryConfig;
import com.payne.reader.bean.send.InventoryParam;
import com.payne.reader.communication.ConnectHandle;
import com.payne.reader.process.ReaderImpl;
import com.rfid.core.AbstractRfidReader;
import com.rfid.core.RfidException;
import com.rfid.core.RfidReaderConfig;
import com.rfid.core.RfidSdkType;
import com.rfid.core.RfidTagEvent;
import com.rfid.core.RfidTagListener;
import com.rfid.transport.JSerialPortHandle;
import com.rfid.util.TagCodeExtractor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class PayneRfidReader extends AbstractRfidReader {

    private static final int PAYNE_POWER_MIN = 1;
    private static final int PAYNE_POWER_MAX = 33;

    private Reader mReader;
    private String portName;
    private volatile boolean connected;
    private volatile boolean continuousReading;
    private volatile RfidTagListener tagListener;
    private volatile boolean manualReadActive;
    private final InventoryParam inventoryParam = new InventoryParam();
    private String readerInfo = "";
    private volatile int nativePower = 16;

    public PayneRfidReader(RfidReaderConfig config) {
        super(config);
        inventoryParam.setAntennaCount(AntennaCount.SINGLE_CHANNEL);
        inventoryParam.setSession(Session.S0);
        inventoryParam.setTarget(Target.A);
        inventoryParam.setRepeat((byte) 1);
        inventoryParam.setDelayMs(0);
        inventoryParam.setLoopCount(-1);
        inventoryParam.clearCustomSessionIds();
        for (int antId : config.getAntennaIds()) {
            inventoryParam.addCustomSessionId(antId);
        }
    }

    @Override
    public RfidSdkType getSdkType() {
        return RfidSdkType.PAYNE;
    }

    @Override
    public void connect(String portName) throws RfidException {
        if (connected) {
            disconnect();
        }
        this.portName = portName;
        ConnectHandle handle = new JSerialPortHandle(portName, config.getBaudRate());
        mReader = ReaderImpl.create(AntennaCount.SINGLE_CHANNEL);
        boolean linkSuccess = mReader.connect(handle);
        if (!linkSuccess) {
            mReader = null;
            throw new RfidException("Falha ao conectar na porta " + portName);
        }
        try {
            mReader.setReconnectByTimeoutTimes(10);
        } catch (Throwable ignored) {
        }
        inventoryParamConfig();
        fetchFirmwareInfo();
        connected = true;
        setPowerPercent(config.getDefaultPowerPercent());
    }

    @Override
    public void disconnect() {
        stopContinuousReading();
        if (mReader != null) {
            try {
                mReader.disconnect();
            } catch (Throwable ignored) {
            }
            mReader = null;
        }
        connected = false;
        portName = null;
    }

    @Override
    public boolean isConnected() {
        return connected && mReader != null;
    }

    @Override
    protected void applyNativePower(int nativePowerValue) throws RfidException {
        if (mReader == null) {
            throw new RfidException("Leitor não conectado");
        }
        nativePower = clamp(nativePowerValue, PAYNE_POWER_MIN, PAYNE_POWER_MAX);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> error = new AtomicReference<>();
        mReader.setOutputPowerUniformly((byte) nativePower,
                success -> latch.countDown(),
                failure -> {
                    error.set("Erro ao definir potência: " + failure.getErrorCode());
                    latch.countDown();
                });
        awaitLatch(latch, error, "Timeout ao definir potência");
    }

    @Override
    protected int percentToNative(int percent) {
        int value = (int) Math.round(percent * 33.0 / 100.0);
        return clamp(value, PAYNE_POWER_MIN, PAYNE_POWER_MAX);
    }

    @Override
    protected int nativeToPercent(int nativePowerValue) {
        int clamped = clamp(nativePowerValue, PAYNE_POWER_MIN, PAYNE_POWER_MAX);
        return (int) Math.round(clamped * 100.0 / 33.0);
    }

    @Override
    public void startContinuousReading(RfidTagListener listener) throws RfidException {
        if (!isConnected()) {
            throw new RfidException("Leitor não conectado");
        }
        if (continuousReading) {
            return;
        }
        manualReadActive = false;
        tagListener = listener;
        continuousReading = true;
        notifyReadingState(true);
        doNextAnt(false);
    }

    @Override
    public void stopContinuousReading() {
        if (!continuousReading && !manualReadActive) {
            return;
        }
        continuousReading = false;
        manualReadActive = false;
        if (mReader != null) {
            try {
                mReader.stopInventory();
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
        tagListener = listener;
        manualReadActive = true;
        notifyReadingState(true);
        doNextAnt(false);

        long deadline = System.currentTimeMillis() + Math.max(200, timeoutMs);
        while (manualReadActive && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (manualReadActive) {
            stopContinuousReading();
        }
    }

    @Override
    public String getReaderInfo() {
        return readerInfo;
    }

    @Override
    public void setAntennaIds(int[] antennaIds) throws RfidException {
        if (antennaIds == null || antennaIds.length == 0) {
            throw new RfidException("Informe ao menos uma antena");
        }
        config.setAntennaIds(antennaIds);
        inventoryParam.clearCustomSessionIds();
        for (int antId : antennaIds) {
            inventoryParam.addCustomSessionId(antId);
        }
    }

    @Override
    public int[] getAntennaIds() {
        return config.getAntennaIds();
    }

    private void inventoryParamConfig() {
        BaseInventory inventory = new CustomSessionTargetInventory.Builder()
                .session(Session.S0)
                .target(Target.A)
                .enablePhase(false)
                .repeat((byte) 1)
                .build();

        InventoryConfig config = new InventoryConfig.Builder()
                .setInventory(inventory)
                .setOnInventoryTagSuccess((Consumer<InventoryTag>) tag -> {
                    String epc = tag != null ? tag.getEpc() : "";
                    String code = TagCodeExtractor.fromEpcHex(epc);
                    if ("0".equals(code.trim())) {
                        return;
                    }
                    dispatchTag(epc, code, tag);
                    if (manualReadActive) {
                        manualReadActive = false;
                        if (mReader != null) {
                            mReader.stopInventory();
                        }
                        notifyReadingState(false);
                    }
                })
                .setOnInventoryTagEndSuccess((Consumer<InventoryTagEnd>) tagEnd -> {
                    if (continuousReading && !manualReadActive) {
                        doNextAnt(true);
                    }
                })
                .setOnFailure((Consumer<InventoryFailure>) failure -> {
                    RfidTagListener l = tagListener;
                    if (l != null) {
                        l.onError(new RfidException("Falha no inventário Payne"));
                    }
                })
                .build();
        mReader.setInventoryConfig(config);
    }

    private void dispatchTag(String epc, String code, InventoryTag tag) {
        RfidTagListener l = tagListener;
        if (l == null) {
            return;
        }
        int rssi = 0;
        int antenna = 0;
        try {
            if (tag != null) {
                antenna = tag.getAntId();
            }
        } catch (Throwable ignored) {
        }
        RfidTagEvent event = new RfidTagEvent(epc, code, rssi, antenna, System.currentTimeMillis());
        l.onTag(event);
    }

    private void doNextAnt(boolean nextAnt) {
        if (mReader == null) {
            return;
        }
        if (!continuousReading && !manualReadActive) {
            return;
        }
        int workAntId = inventoryParam.getAntennaId(nextAnt);
        mReader.setWorkAntenna(workAntId, success -> mReader.startInventory(), failure -> doNextAnt(true));
    }

    private void fetchFirmwareInfo() {
        if (mReader == null) {
            return;
        }
        CountDownLatch latch = new CountDownLatch(1);
        mReader.getFirmwareVersion(
                (Consumer<Version>) version -> {
                    readerInfo = version.getChipType() + " V" + version.getVersion();
                    latch.countDown();
                },
                failure -> {
                    readerInfo = "Payne (firmware desconhecido)";
                    latch.countDown();
                });
        try {
            latch.await(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            readerInfo = "Payne";
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

    private static void awaitLatch(CountDownLatch latch, AtomicReference<String> error, String timeoutMsg)
            throws RfidException {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new RfidException(timeoutMsg);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RfidException("Operação interrompida", e);
        }
        if (error.get() != null) {
            throw new RfidException(error.get());
        }
    }
}
