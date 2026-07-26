package com.peripheral.workflow;

import com.peripheral.pedido.PedidoItem;
import com.peripheral.pedido.PedidoSerial;
import com.peripheral.pedido.PedidoVolume;
import com.peripheral.scale.ScaleWeightFormat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class PedidoValidationService {

    public enum ValidationStatus {
        OK,
        UNKNOWN_SERIALS,
        QUANTITY_MISMATCH,
        WEIGHT_MISMATCH,
        MISSING_SERIALS
    }

    public static final class ValidationResult {
        private final boolean valid;
        private final ValidationStatus status;
        private final List<String> messages;
        private final List<String> unknownSerials;
        private final double expectedWeightKg;
        private final double actualWeightKg;

        public ValidationResult(boolean valid, ValidationStatus status, List<String> messages,
                                List<String> unknownSerials, double expectedWeightKg, double actualWeightKg) {
            this.valid = valid;
            this.status = status;
            this.messages = messages != null ? messages : Collections.emptyList();
            this.unknownSerials = unknownSerials != null ? unknownSerials : Collections.emptyList();
            this.expectedWeightKg = expectedWeightKg;
            this.actualWeightKg = actualWeightKg;
        }

        public boolean isValid() {
            return valid;
        }

        public ValidationStatus getStatus() {
            return status;
        }

        public List<String> getMessages() {
            return messages;
        }

        public List<String> getUnknownSerials() {
            return unknownSerials;
        }

        /** @deprecated use {@link #getUnknownSerials()} */
        @Deprecated
        public List<String> getUnknownTags() {
            return unknownSerials;
        }

        public double getExpectedWeightKg() {
            return expectedWeightKg;
        }

        public double getActualWeightKg() {
            return actualWeightKg;
        }

        public String getSummaryMessage() {
            if (messages.isEmpty()) {
                return valid ? "Validação OK" : "Divergência detectada";
            }
            return String.join("; ", messages);
        }
    }

    public ValidationResult validate(PedidoVolume volume, Set<String> readSerials, double weightKg,
                                     double tolerancePercent, double toleranceKg) {
        List<String> messages = new ArrayList<>();
        Set<String> expectedSerials = new LinkedHashSet<>();
        double expectedWeight = 0;

        if (volume != null) {
            for (PedidoItem item : volume.getItens()) {
                expectedWeight += item.getPesoTotalEsperadoKg();
                if (item.hasSeriais()) {
                    for (PedidoSerial serial : item.getSeriais()) {
                        expectedSerials.add(serial.getSerial());
                    }
                }
            }
        }

        if (expectedSerials.isEmpty()) {
            return validateLegacyByProductCode(volume, readSerials, weightKg, tolerancePercent, toleranceKg);
        }

        Set<String> readSet = new HashSet<>();
        List<String> unknownSerials = new ArrayList<>();
        for (String serial : readSerials) {
            if (serial == null || serial.trim().isEmpty()) {
                continue;
            }
            String normalized = serial.trim();
            readSet.add(normalized);
            if (!expectedSerials.contains(normalized)) {
                unknownSerials.add(normalized);
            }
        }

        if (!unknownSerials.isEmpty()) {
            messages.add("Seriais não pertencem ao pedido: " + String.join(", ", unknownSerials));
            return new ValidationResult(false, ValidationStatus.UNKNOWN_SERIALS, messages,
                    unknownSerials, expectedWeight, weightKg);
        }

        List<String> missing = new ArrayList<>();
        for (String expected : expectedSerials) {
            if (!readSet.contains(expected)) {
                missing.add(expected);
            }
        }
        if (!missing.isEmpty()) {
            messages.add("Seriais faltantes: " + String.join(", ", missing));
            return new ValidationResult(false, ValidationStatus.MISSING_SERIALS, messages,
                    unknownSerials, expectedWeight, weightKg);
        }

        if (readSet.size() != expectedSerials.size()) {
            messages.add(String.format("Quantidade de seriais divergente (esperado %d, lido %d)",
                    expectedSerials.size(), readSet.size()));
            return new ValidationResult(false, ValidationStatus.QUANTITY_MISMATCH, messages,
                    unknownSerials, expectedWeight, weightKg);
        }

        return finishWeightCheck(messages, unknownSerials, expectedWeight, weightKg,
                tolerancePercent, toleranceKg);
    }

    private ValidationResult validateLegacyByProductCode(PedidoVolume volume, Set<String> tagCodes,
                                                         double weightKg, double tolerancePercent,
                                                         double toleranceKg) {
        List<String> messages = new ArrayList<>();
        java.util.Map<String, Integer> expectedCounts = new java.util.LinkedHashMap<>();
        Set<String> expectedCodes = new HashSet<>();
        double expectedWeight = 0;

        if (volume != null) {
            for (PedidoItem item : volume.getItens()) {
                expectedCounts.put(item.getCodigoProduto(), item.getQuantidadeEsperada());
                expectedCodes.add(item.getCodigoProduto());
                expectedWeight += item.getPesoTotalEsperadoKg();
            }
        }

        java.util.Map<String, Integer> actualCounts = new java.util.HashMap<>();
        List<String> unknown = new ArrayList<>();
        for (String tag : tagCodes) {
            if (tag == null || tag.trim().isEmpty()) {
                continue;
            }
            String code = tag.trim();
            if (!expectedCodes.contains(code)) {
                unknown.add(code);
                continue;
            }
            actualCounts.merge(code, 1, Integer::sum);
        }

        if (!unknown.isEmpty()) {
            messages.add("Tags não pertencem ao pedido: " + String.join(", ", unknown));
            return new ValidationResult(false, ValidationStatus.UNKNOWN_SERIALS, messages,
                    unknown, expectedWeight, weightKg);
        }

        for (java.util.Map.Entry<String, Integer> entry : expectedCounts.entrySet()) {
            int expected = entry.getValue();
            int actual = actualCounts.getOrDefault(entry.getKey(), 0);
            if (actual != expected) {
                messages.add("Quantidade divergente para " + entry.getKey()
                        + " (esperado " + expected + ", lido " + actual + ")");
            }
        }

        if (!messages.isEmpty()) {
            return new ValidationResult(false, ValidationStatus.QUANTITY_MISMATCH, messages,
                    unknown, expectedWeight, weightKg);
        }

        return finishWeightCheck(messages, unknown, expectedWeight, weightKg,
                tolerancePercent, toleranceKg);
    }

    private ValidationResult finishWeightCheck(List<String> messages, List<String> unknown,
                                               double expectedWeight, double weightKg,
                                               double tolerancePercent, double toleranceKg) {
        double percentTol = expectedWeight * (tolerancePercent / 100.0);
        double allowedDelta = Math.max(toleranceKg, percentTol);
        double delta = Math.abs(weightKg - expectedWeight);
        if (delta > allowedDelta) {
            messages.add(String.format(
                    "Peso divergente (esperado %s, lido %s, tolerância ±%s)",
                    ScaleWeightFormat.formatGramsPlain(expectedWeight),
                    ScaleWeightFormat.formatGramsPlain(weightKg),
                    ScaleWeightFormat.formatGramsPlain(allowedDelta)));
            return new ValidationResult(false, ValidationStatus.WEIGHT_MISMATCH, messages,
                    unknown, expectedWeight, weightKg);
        }

        return new ValidationResult(true, ValidationStatus.OK,
                Collections.singletonList("Peso e códigos conferem com o pedido."),
                unknown, expectedWeight, weightKg);
    }
}
