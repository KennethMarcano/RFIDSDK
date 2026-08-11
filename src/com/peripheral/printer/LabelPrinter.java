package com.peripheral.printer;

import com.peripheral.core.PeripheralException;

/**
 * Impressora térmica ZPL (write-only). Não faz inventário como RFID/balança.
 */
public interface LabelPrinter {

    void printZpl(String zpl) throws PeripheralException;

    float getLabelWidthMm();

    float getLabelHeightMm();

    void setLabelSizeMm(float widthMm, float heightMm);
}
