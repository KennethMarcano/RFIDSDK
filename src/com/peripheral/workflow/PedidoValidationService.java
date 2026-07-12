package com.peripheral.workflow;

import com.peripheral.pedido.PedidoItem;
import com.peripheral.pedido.PedidoVolume;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class PedidoValidationService {

    public enum ValidationStatus {
        OK,
        UNKNOWN_TAGS,
        QUANTITY_MISMATCH,
        WEIGHT_MISMATCH,
        MISSING_TAGS
    }

    public static final class ValidationResult {
        private final boolean valid;
        private final ValidationStatus status;
        private final List<String> messages;
        private final List<String> unknownTags;
        private final double expectedWeightKg;
        private final double actualWeightKg;

        public ValidationResult(boolean valid, ValidationStatus status, List<String> messages,
                                List<String> unknownTags, double expectedWeightKg, double actualWeightKg) {
            this.valid = valid;
            this.status = status;
            this.messages = messages != null ? messages : Collections.emptyList();
            this.unknownTags = unknownTags != null ? unknownTags : Collections.emptyList();
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

        public List<String> getUnknownTags() {
            return unknownTags;
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

    public ValidationResult validate(PedidoVolume volume, Set<String> tagCodes, double weightKg,
                                     double tolerancePercent, double toleranceKg) {
        List<String> messages = new ArrayList<>();
        Map<String, Integer> expectedCounts = new LinkedHashMap<>();
        Set<String> expectedCodes = new HashSet<>();
        double expectedWeight = 0;

        if (volume != null) {
            for (PedidoItem item : volume.getItens()) {
                expectedCounts.put(item.getCodigoProduto(), item.getQuantidadeEsperada());
                expectedCodes.add(item.getCodigoProduto());
                expectedWeight += item.getPesoTotalEsperadoKg();
            }
        }

        Map<String, Integer> actualCounts = new HashMap<>();
        List<String> unknownTags = new ArrayList<>();
        for (String tag : tagCodes) {
            if (tag == null || tag.trim().isEmpty()) {
                continue;
            }
            String code = tag.trim();
            if (!expectedCodes.contains(code)) {
                unknownTags.add(code);
                continue;
            }
            actualCounts.merge(code, 1, Integer::sum);
        }

        if (!unknownTags.isEmpty()) {
            messages.add("Tags não pertencem ao pedido: " + String.join(", ", unknownTags));
            return new ValidationResult(false, ValidationStatus.UNKNOWN_TAGS, messages,
                    unknownTags, expectedWeight, weightKg);
        }

        for (Map.Entry<String, Integer> entry : expectedCounts.entrySet()) {
            int expected = entry.getValue();
            int actual = actualCounts.getOrDefault(entry.getKey(), 0);
            if (actual != expected) {
                messages.add("Quantidade divergente para " + entry.getKey()
                        + " (esperado " + expected + ", lido " + actual + ")");
            }
        }

        for (Map.Entry<String, Integer> entry : actualCounts.entrySet()) {
            if (!expectedCounts.containsKey(entry.getKey())) {
                messages.add("Tag inesperada: " + entry.getKey());
            }
        }

        if (!messages.isEmpty()) {
            return new ValidationResult(false, ValidationStatus.QUANTITY_MISMATCH, messages,
                    unknownTags, expectedWeight, weightKg);
        }

        for (Map.Entry<String, Integer> entry : expectedCounts.entrySet()) {
            if (actualCounts.getOrDefault(entry.getKey(), 0) < entry.getValue()) {
                messages.add("Faltam tags para produto " + entry.getKey());
            }
        }
        if (!messages.isEmpty()) {
            return new ValidationResult(false, ValidationStatus.MISSING_TAGS, messages,
                    unknownTags, expectedWeight, weightKg);
        }

        double percentTol = expectedWeight * (tolerancePercent / 100.0);
        double allowedDelta = Math.max(toleranceKg, percentTol);
        double delta = Math.abs(weightKg - expectedWeight);
        if (delta > allowedDelta) {
            messages.add(String.format(
                    "Peso divergente (esperado %.3f kg, lido %.3f kg, tolerância ±%.3f kg)",
                    expectedWeight, weightKg, allowedDelta));
            return new ValidationResult(false, ValidationStatus.WEIGHT_MISMATCH, messages,
                    unknownTags, expectedWeight, weightKg);
        }

        return new ValidationResult(true, ValidationStatus.OK,
                Collections.singletonList("Peso e tags conferem com o pedido."),
                unknownTags, expectedWeight, weightKg);
    }
}
