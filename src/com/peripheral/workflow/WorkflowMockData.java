package com.peripheral.workflow;

import java.util.ArrayList;
import java.util.List;

public final class WorkflowMockData {

    public static final String DEFAULT_TAGS_TEXT =
            "BOX-001:E2801160600002033A1B2C3D4, BOX-002:E2801160600002033A1B2C3D5, PALLET-99";

    private WorkflowMockData() {
    }

    public static WorkflowMockScenario sample(boolean fastStabilization) {
        return new WorkflowMockScenario(3.125, parseTags(DEFAULT_TAGS_TEXT), fastStabilization);
    }

    public static WorkflowMockScenario fromInput(double weightKg, String tagsText, boolean fastStabilization) {
        return new WorkflowMockScenario(weightKg, parseTags(tagsText), fastStabilization);
    }

    public static List<WorkflowMockScenario.MockTag> parseTags(String tagsText) {
        List<WorkflowMockScenario.MockTag> tags = new ArrayList<>();
        if (tagsText == null || tagsText.trim().isEmpty()) {
            return tags;
        }
        for (String part : tagsText.split("[,;\\n]+")) {
            String token = part.trim();
            if (token.isEmpty()) {
                continue;
            }
            int colon = token.indexOf(':');
            if (colon > 0) {
                String code = token.substring(0, colon).trim();
                String epc = token.substring(colon + 1).trim();
                if (!code.isEmpty()) {
                    tags.add(new WorkflowMockScenario.MockTag(code, epc.isEmpty() ? mockEpc(code) : epc));
                }
            } else {
                tags.add(new WorkflowMockScenario.MockTag(token, mockEpc(token)));
            }
        }
        return tags;
    }

    public static String formatTagsForDisplay(List<WorkflowMockScenario.MockTag> tags) {
        if (tags == null || tags.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (WorkflowMockScenario.MockTag tag : tags) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(tag.getCode());
            if (tag.getEpc() != null && !tag.getEpc().isEmpty()) {
                sb.append(':').append(tag.getEpc());
            }
        }
        return sb.toString();
    }

    private static String mockEpc(String code) {
        String normalized = code.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
        if (normalized.isEmpty()) {
            normalized = "TAG";
        }
        return "MOCK" + normalized;
    }
}
