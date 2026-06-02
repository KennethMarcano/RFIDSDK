package com.peripheral.workflow;

public enum WorkflowStep {

    WEIGHING("Pesagem"),
    RFID_READ("Leitura RFID"),
    CAPTURE_PHOTO("Capturar foto"),
    PRINT_LABEL("Imprimir etiqueta");

    private final String label;

    WorkflowStep(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
