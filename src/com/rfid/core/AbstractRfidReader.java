package com.rfid.core;

public abstract class AbstractRfidReader implements RfidReader {

    protected final RfidReaderConfig config;
    protected volatile int currentPowerPercent;

    protected AbstractRfidReader(RfidReaderConfig config) {
        this.config = config != null ? config : new RfidReaderConfig();
        this.currentPowerPercent = this.config.getDefaultPowerPercent();
    }

    protected static void validatePowerPercent(int percent) throws RfidException {
        if (percent < 1 || percent > 100) {
            throw new RfidException("Potência deve estar entre 1 e 100%");
        }
    }

    @Override
    public void setPowerPercent(int percent) throws RfidException {
        validatePowerPercent(percent);
        int nativePower = percentToNative(percent);
        applyNativePower(nativePower);
        currentPowerPercent = percent;
    }

    @Override
    public int getPowerPercent() {
        return currentPowerPercent;
    }

    protected abstract void applyNativePower(int nativePower) throws RfidException;

    protected abstract int percentToNative(int percent);

    protected abstract int nativeToPercent(int nativePower);
}
