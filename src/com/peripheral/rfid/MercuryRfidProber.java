package com.peripheral.rfid;

import com.peripheral.core.PortProbeResult;
import com.peripheral.core.SerialConnectionConfig;
import com.peripheral.core.SerialPortProber;
import com.rfid.core.RfidException;
import com.rfid.core.RfidReaderConfig;
import com.rfid.impl.MercuryRfidReader;

public class MercuryRfidProber implements SerialPortProber {

    @Override
    public PortProbeResult probe(SerialConnectionConfig config, long timeoutMs) {
        if (config == null || config.getPortName() == null || config.getPortName().trim().isEmpty()) {
            return PortProbeResult.openFailed("Porta serial não informada", "");
        }
        String port = config.getPortName().trim();
        MercuryRfidReader reader = new MercuryRfidReader(new RfidReaderConfig().setDefaultPowerPercent(50));
        try {
            reader.connect(port);
            String info = reader.getReaderInfo();
            if (info == null || info.trim().isEmpty()) {
                info = "ThingMagic Mercury";
            }
            return PortProbeResult.match(
                    "Leitor RFID Mercury detectado na porta " + port + ".",
                    info);
        } catch (RfidException e) {
            return PayneRfidProber.classifyRfidFailure(port, e.getMessage(), false);
        } finally {
            reader.disconnect();
        }
    }
}
