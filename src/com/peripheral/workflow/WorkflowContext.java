package com.peripheral.workflow;

import com.peripheral.pedido.PedidoVolume;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class WorkflowContext {

    private final Object tagLock = new Object();

    private double weightKg;
    private double grossWeightKg;
    private double tareKg;
    private boolean tareActive;
    private boolean weightStable;
    private long cycleStartedMs;
    private String photoPath;
    private String labelPdfPath;
    private String labelZplPath;
    private final Set<String> tagCodes = new LinkedHashSet<>();
    private final Set<String> tagEpcs = new LinkedHashSet<>();

    private String numeroPedido;
    private int volumeIndex;
    private PedidoVolume currentVolume;
    private PedidoValidationService.ValidationResult validationResult;
    private String aiMessage;
    private List<String> missingProducts;
    private String validationStatusLabel;
    private boolean operatorConfirmed;
    private boolean operatorOverride;

    /**
     * Inicia um ciclo de validação preservando as tags já acumuladas
     * (RFID contínuo) e registrando o peso líquido estabilizado.
     */
    public void beginCycle(double netWeightKg, boolean weightStable) {
        this.weightKg = netWeightKg;
        this.weightStable = weightStable;
        this.cycleStartedMs = System.currentTimeMillis();
        this.photoPath = null;
        this.labelPdfPath = null;
        this.labelZplPath = null;
        this.validationResult = null;
        this.aiMessage = null;
        this.missingProducts = null;
        this.validationStatusLabel = null;
        this.operatorConfirmed = false;
        this.operatorOverride = false;
    }

    public double getWeightKg() {
        return weightKg;
    }

    public double getGrossWeightKg() {
        return grossWeightKg;
    }

    public double getNetWeightKg() {
        return weightKg;
    }

    public double getTareKg() {
        return tareKg;
    }

    public boolean isTareActive() {
        return tareActive;
    }

    public boolean isWeightStable() {
        return weightStable;
    }

    public long getCycleStartedMs() {
        return cycleStartedMs;
    }

    public String getPhotoPath() {
        return photoPath;
    }

    public void setPhotoPath(String photoPath) {
        this.photoPath = photoPath;
    }

    public String getLabelPdfPath() {
        return labelPdfPath;
    }

    public void setLabelPdfPath(String labelPdfPath) {
        this.labelPdfPath = labelPdfPath;
    }

    public String getLabelZplPath() {
        return labelZplPath;
    }

    public void setLabelZplPath(String labelZplPath) {
        this.labelZplPath = labelZplPath;
    }

    public boolean addTag(String epc, String code) {
        synchronized (tagLock) {
            if (epc != null && !epc.trim().isEmpty()) {
                if (!tagEpcs.add(epc.trim())) {
                    return false;
                }
            }
            if (code != null && !code.trim().isEmpty()) {
                return tagCodes.add(code.trim());
            }
            return epc != null && !epc.trim().isEmpty();
        }
    }

    public Set<String> getTagCodes() {
        synchronized (tagLock) {
            return Collections.unmodifiableSet(new LinkedHashSet<>(tagCodes));
        }
    }

    public Set<String> getTagEpcs() {
        synchronized (tagLock) {
            return Collections.unmodifiableSet(new LinkedHashSet<>(tagEpcs));
        }
    }

    /** Snapshot atômico para validação sem corrida com o callback RFID. */
    public Set<String> snapshotTagCodes() {
        return getTagCodes();
    }

    public int getDetectedTagCount() {
        synchronized (tagLock) {
            return tagCodes.size();
        }
    }

    public void updateWeight(double netWeightKg, boolean weightStable) {
        this.weightKg = netWeightKg;
        this.weightStable = weightStable;
    }

    public void updateScaleReading(double grossKg, boolean stable) {
        this.grossWeightKg = grossKg;
        this.weightKg = tareActive ? (grossKg - tareKg) : grossKg;
        this.weightStable = stable;
    }

    public boolean applyTare(double grossKg) {
        if (grossKg < 0) {
            return false;
        }
        this.tareKg = grossKg;
        this.tareActive = true;
        this.grossWeightKg = grossKg;
        this.weightKg = 0;
        return true;
    }

    public void clearTare() {
        this.tareKg = 0;
        this.tareActive = false;
        this.weightKg = this.grossWeightKg;
    }

    public String getNumeroPedido() {
        return numeroPedido;
    }

    public void setNumeroPedido(String numeroPedido) {
        this.numeroPedido = numeroPedido;
    }

    public int getVolumeIndex() {
        return volumeIndex;
    }

    public void setVolumeIndex(int volumeIndex) {
        this.volumeIndex = volumeIndex;
    }

    public PedidoVolume getCurrentVolume() {
        return currentVolume;
    }

    public void setCurrentVolume(PedidoVolume currentVolume) {
        this.currentVolume = currentVolume;
    }

    public PedidoValidationService.ValidationResult getValidationResult() {
        return validationResult;
    }

    public void setValidationResult(PedidoValidationService.ValidationResult validationResult) {
        this.validationResult = validationResult;
    }

    public String getAiMessage() {
        return aiMessage;
    }

    public void setAiMessage(String aiMessage) {
        this.aiMessage = aiMessage;
    }

    public List<String> getMissingProducts() {
        return missingProducts;
    }

    public void setMissingProducts(List<String> missingProducts) {
        this.missingProducts = missingProducts;
    }

    public String getValidationStatusLabel() {
        return validationStatusLabel;
    }

    public void setValidationStatusLabel(String validationStatusLabel) {
        this.validationStatusLabel = validationStatusLabel;
    }

    public boolean isOperatorConfirmed() {
        return operatorConfirmed;
    }

    public void setOperatorConfirmed(boolean operatorConfirmed) {
        this.operatorConfirmed = operatorConfirmed;
    }

    public boolean isOperatorOverride() {
        return operatorOverride;
    }

    public void setOperatorOverride(boolean operatorOverride) {
        this.operatorOverride = operatorOverride;
    }

    public void clearTags() {
        synchronized (tagLock) {
            tagCodes.clear();
            tagEpcs.clear();
        }
    }

    public List<String> listDetectedCodes() {
        synchronized (tagLock) {
            return new ArrayList<>(tagCodes);
        }
    }
}
