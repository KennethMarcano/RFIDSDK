package com.peripheral.workflow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class WorkflowMockScenario {

    public static final class MockTag {
        private final String code;
        private final String epc;

        public MockTag(String code, String epc) {
            this.code = code != null ? code.trim() : "";
            this.epc = epc != null ? epc.trim() : "";
        }

        public String getCode() {
            return code;
        }

        public String getEpc() {
            return epc;
        }
    }

    private final double weightKg;
    private final List<MockTag> tags;
    private final boolean fastStabilization;

    public WorkflowMockScenario(double weightKg, List<MockTag> tags, boolean fastStabilization) {
        this.weightKg = weightKg;
        this.tags = tags != null ? Collections.unmodifiableList(new ArrayList<>(tags)) : Collections.emptyList();
        this.fastStabilization = fastStabilization;
    }

    public double getWeightKg() {
        return weightKg;
    }

    public List<MockTag> getTags() {
        return tags;
    }

    public boolean isFastStabilization() {
        return fastStabilization;
    }
}
