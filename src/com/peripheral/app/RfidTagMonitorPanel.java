package com.peripheral.app;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Monitor RFID: lista códigos únicos detectados.
 * No fluxo de operação ({@code showReadCounts=false}) não exibe "Nx"/leituras —
 * uma detecção basta — e pode pré-listar todos os produtos esperados do pedido.
 */
public class RfidTagMonitorPanel extends JPanel {

    public static final class ProductEntry {
        private final String rowId;
        private final String code;
        private final String name;

        public ProductEntry(String code, String name) {
            this(null, code, name);
        }

        public ProductEntry(String rowId, String code, String name) {
            this.code = code != null ? code.trim() : "";
            this.name = name != null ? name.trim() : "";
            this.rowId = (rowId != null && !rowId.trim().isEmpty())
                    ? rowId.trim()
                    : this.code;
        }

        public String getRowId() {
            return rowId;
        }

        public String getCode() {
            return code;
        }

        public String getName() {
            return name;
        }
    }

    private static final class RowWidgets {
        final JPanel root;
        final JLabel lbCode;
        final JLabel lbName;
        final JLabel lbStatus;

        RowWidgets(JPanel root, JLabel lbCode, JLabel lbName, JLabel lbStatus) {
            this.root = root;
            this.lbCode = lbCode;
            this.lbName = lbName;
            this.lbStatus = lbStatus;
        }
    }

    private final boolean showReadCounts;
    private final Map<String, Integer> counts = new LinkedHashMap<>();
    private final Map<String, RowWidgets> rowsByKey = new LinkedHashMap<>();
    private final List<ProductEntry> expectedProducts = new ArrayList<>();
    private final Set<String> detectedKeys = new LinkedHashSet<>();

    private final JLabel lbCaption;
    private final JLabel lbUniqueValue = new JLabel("0");
    private final JLabel lbTotalValue = new JLabel("0");
    private final JPanel countersHost = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
    private final JLabel lbHint = new JLabel("Aguardando leitura das tags...");
    private final JPanel rows = new JPanel();

    private int totalReads;
    private int expectedCount;

    public RfidTagMonitorPanel(String caption) {
        this(caption, true);
    }

    public RfidTagMonitorPanel(String caption, boolean showReadCounts) {
        super(new BorderLayout(0, 6));
        this.showReadCounts = showReadCounts;
        setOpaque(true);
        setBackground(WorkflowUiTheme.MONITOR_BG);
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(WorkflowUiTheme.MONITOR_BORDER, 1),
                WorkflowUiTheme.empty(10, 12, 10, 12)));
        setAlignmentX(Component.LEFT_ALIGNMENT);

        lbCaption = new JLabel(caption);
        lbCaption.setFont(lbCaption.getFont().deriveFont(Font.BOLD, 12f));
        lbCaption.setForeground(WorkflowUiTheme.MONITOR_CAPTION);

        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
        rows.setOpaque(true);
        rows.setBackground(WorkflowUiTheme.MONITOR_BG);

        lbHint.setFont(lbHint.getFont().deriveFont(Font.PLAIN, 12f));
        lbHint.setForeground(WorkflowUiTheme.MONITOR_CAPTION);
        lbHint.setAlignmentX(Component.LEFT_ALIGNMENT);
        lbHint.setBorder(WorkflowUiTheme.empty(6, 2, 6, 2));
        rows.add(lbHint);

        JPanel listHost = new JPanel(new BorderLayout());
        listHost.setOpaque(true);
        listHost.setBackground(WorkflowUiTheme.MONITOR_BG);
        listHost.add(rows, BorderLayout.NORTH);

        JScrollPane scroll = new JScrollPane(listHost);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setOpaque(false);
        scroll.getViewport().setBackground(WorkflowUiTheme.MONITOR_BG);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(20);

        add(buildSummaryBar(), BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        rebuildSummaryCounters();
    }

    private JPanel buildSummaryBar() {
        countersHost.setOpaque(false);

        JPanel bar = new JPanel(new BorderLayout(8, 0));
        bar.setOpaque(false);
        bar.add(lbCaption, BorderLayout.WEST);
        bar.add(countersHost, BorderLayout.EAST);
        return bar;
    }

    private void rebuildSummaryCounters() {
        countersHost.removeAll();
        if (showReadCounts) {
            countersHost.add(buildCounter(lbUniqueValue, "TAGS"));
            countersHost.add(buildCounter(lbTotalValue, "LEITURAS"));
        } else if (expectedCount > 0) {
            countersHost.add(buildCounter(lbUniqueValue, "DETECTADOS"));
            countersHost.add(buildCounter(lbTotalValue, "ESPERADOS"));
        } else {
            countersHost.add(buildCounter(lbUniqueValue, "CÓDIGOS"));
        }
        countersHost.revalidate();
        countersHost.repaint();
    }

    private JPanel buildCounter(JLabel valueLabel, String caption) {
        valueLabel.setFont(valueLabel.getFont().deriveFont(Font.BOLD, 20f));
        valueLabel.setForeground(Color.WHITE);

        JLabel lbCounterCaption = new JLabel(caption);
        lbCounterCaption.setFont(lbCounterCaption.getFont().deriveFont(Font.BOLD, 10f));
        lbCounterCaption.setForeground(WorkflowUiTheme.MONITOR_CAPTION);
        lbCounterCaption.setBorder(WorkflowUiTheme.empty(6, 3, 0, 8));

        JPanel counter = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        counter.setOpaque(false);
        counter.add(valueLabel);
        counter.add(lbCounterCaption);
        return counter;
    }

    /**
     * Pré-lista todos os produtos esperados do volume/pedido.
     * Limpa detecções anteriores (nova lista = novo inventário visual).
     */
    public void setExpectedProducts(List<ProductEntry> products) {
        expectedProducts.clear();
        detectedKeys.clear();
        counts.clear();
        totalReads = 0;
        if (products != null) {
            for (ProductEntry entry : products) {
                if (entry != null && entry.getCode() != null && !entry.getCode().isEmpty()) {
                    expectedProducts.add(entry);
                }
            }
        }
        expectedCount = expectedProducts.size();
        rebuildRowsFromState();
        rebuildSummaryCounters();
        updateSummary();
    }

    /** Alias conveniente só com códigos. */
    public void setExpectedCodes(Collection<String> codes) {
        List<ProductEntry> entries = new ArrayList<>();
        if (codes != null) {
            for (String code : codes) {
                if (code != null && !code.trim().isEmpty()) {
                    entries.add(new ProductEntry(code.trim(), ""));
                }
            }
        }
        setExpectedProducts(entries);
    }

    /** Registra a tag. Em modo fluxo, uma detecção basta (sem contagem). */
    public void registerTag(String code) {
        if (code == null) {
            return;
        }
        String normalized = normalizeKey(code);
        if (normalized.isEmpty()) {
            return;
        }

        if (showReadCounts) {
            totalReads++;
            Integer previous = counts.get(normalized);
            int updated = previous == null ? 1 : previous + 1;
            counts.put(normalized, updated);
            detectedKeys.add(normalized);
            if (previous == null) {
                ensureHintRemoved();
                if (!rowsByKey.containsKey(normalized)) {
                    rows.add(createTagRow(normalized, code.trim(), "", true, updated));
                }
            } else {
                RowWidgets widgets = rowsByKey.get(normalized);
                if (widgets != null) {
                    applyCount(widgets.lbStatus, updated);
                }
            }
        } else {
            String rowId = findFirstPendingRowIdForCode(normalized);
            if (rowId == null) {
                // Já detectado todos os esperados com esse código, ou código extra.
                if (detectedKeys.contains(normalized) || hasDetectedRowForCode(normalized)) {
                    return;
                }
                rowId = normalized;
                detectedKeys.add(rowId);
                ensureHintRemoved();
                ProductEntry expected = findExpectedByCode(normalized);
                String displayCode = expected != null ? expected.getCode() : code.trim();
                String name = expected != null ? expected.getName() : "";
                rows.add(createTagRow(rowId, displayCode, name, true, 1));
            } else {
                detectedKeys.add(rowId);
                ensureHintRemoved();
                RowWidgets existing = rowsByKey.get(rowId);
                if (existing != null) {
                    markDetected(existing);
                }
            }
        }
        rows.revalidate();
        rows.repaint();
        updateSummary();
    }

    /**
     * Sincroniza detecções a partir da lista do orquestrador (códigos únicos).
     * Útil no fluxo para marcar esperados sem incrementar leituras.
     */
    public void syncDetectedCodes(Collection<String> codes) {
        if (showReadCounts || codes == null) {
            return;
        }
        for (String code : codes) {
            registerTag(code);
        }
        updateSummary();
    }

    /** Limpa tudo (detecções e lista esperada). */
    public void reset() {
        counts.clear();
        detectedKeys.clear();
        rowsByKey.clear();
        expectedProducts.clear();
        expectedCount = 0;
        totalReads = 0;
        rows.removeAll();
        rows.add(lbHint);
        rebuildSummaryCounters();
        rows.revalidate();
        rows.repaint();
        updateSummary();
    }

    /**
     * Limpa só as detecções e mantém os produtos esperados visíveis
     * (ex.: ao iniciar pesagem / reler tags).
     */
    public void clearDetections() {
        counts.clear();
        detectedKeys.clear();
        totalReads = 0;
        rebuildRowsFromState();
        updateSummary();
    }

    public void setHint(String text) {
        lbHint.setText(text);
    }

    public int getUniqueTagCount() {
        return showReadCounts ? counts.size() : detectedKeys.size();
    }

    public int getTotalReads() {
        return totalReads;
    }

    private void rebuildRowsFromState() {
        rows.removeAll();
        rowsByKey.clear();
        if (expectedProducts.isEmpty() && detectedKeys.isEmpty()) {
            rows.add(lbHint);
            rows.revalidate();
            rows.repaint();
            return;
        }
        for (ProductEntry entry : expectedProducts) {
            String rowId = normalizeKey(entry.getRowId());
            boolean detected = detectedKeys.contains(rowId);
            rows.add(createTagRow(rowId, entry.getCode(), entry.getName(), detected, 1));
        }
        for (String key : new ArrayList<>(detectedKeys)) {
            if (rowsByKey.containsKey(key)) {
                continue;
            }
            String display = key;
            Integer count = counts.getOrDefault(key, 1);
            rows.add(createTagRow(key, display, "Extra", true, count));
        }
        rows.revalidate();
        rows.repaint();
    }

    private String findFirstPendingRowIdForCode(String normalizedCode) {
        for (ProductEntry entry : expectedProducts) {
            String rowId = normalizeKey(entry.getRowId());
            if (!normalizeKey(entry.getCode()).equals(normalizedCode)) {
                continue;
            }
            if (!detectedKeys.contains(rowId)) {
                return rowId;
            }
        }
        return null;
    }

    private boolean hasDetectedRowForCode(String normalizedCode) {
        for (ProductEntry entry : expectedProducts) {
            String rowId = normalizeKey(entry.getRowId());
            if (normalizeKey(entry.getCode()).equals(normalizedCode) && detectedKeys.contains(rowId)) {
                return true;
            }
        }
        return detectedKeys.contains(normalizedCode);
    }

    private ProductEntry findExpectedByCode(String normalizedCode) {
        for (ProductEntry entry : expectedProducts) {
            if (normalizeKey(entry.getCode()).equals(normalizedCode)) {
                return entry;
            }
        }
        return null;
    }

    private void ensureHintRemoved() {
        if (lbHint.getParent() == rows) {
            rows.remove(lbHint);
        }
    }

    private void updateSummary() {
        int detected = showReadCounts ? counts.size() : detectedKeys.size();
        lbUniqueValue.setText(String.valueOf(detected));
        if (showReadCounts) {
            lbTotalValue.setText(String.valueOf(totalReads));
        } else if (expectedCount > 0) {
            lbTotalValue.setText(String.valueOf(expectedCount));
        }
        boolean complete = expectedCount > 0 && detected >= expectedCount;
        lbUniqueValue.setForeground(detected == 0
                ? Color.WHITE
                : (complete ? WorkflowUiTheme.MONITOR_VALUE : Color.WHITE));
    }

    private JPanel createTagRow(String key, String code, String name, boolean detected, int count) {
        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setOpaque(true);
        row.setBackground(WorkflowUiTheme.MONITOR_ROW_BG);
        row.setBorder(WorkflowUiTheme.empty(8, 10, 8, 10));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, name != null && !name.isEmpty() ? 48 : 38));

        JLabel lbCode = new JLabel(code);
        lbCode.setFont(WorkflowUiTheme.fontTagCode(lbCode));
        lbCode.setForeground(WorkflowUiTheme.MONITOR_TEXT);
        lbCode.setToolTipText("Código: " + code);

        JLabel lbName = new JLabel(name != null ? name : "");
        lbName.setFont(lbName.getFont().deriveFont(Font.PLAIN, 11f));
        lbName.setForeground(WorkflowUiTheme.MONITOR_CAPTION);
        lbName.setVisible(name != null && !name.isEmpty());

        JPanel textCol = new JPanel();
        textCol.setOpaque(false);
        textCol.setLayout(new BoxLayout(textCol, BoxLayout.Y_AXIS));
        lbCode.setAlignmentX(Component.LEFT_ALIGNMENT);
        lbName.setAlignmentX(Component.LEFT_ALIGNMENT);
        textCol.add(lbCode);
        if (lbName.isVisible()) {
            textCol.add(Box.createVerticalStrut(2));
            textCol.add(lbName);
        }

        JLabel lbStatus = new JLabel("", SwingConstants.RIGHT);
        lbStatus.setFont(lbStatus.getFont().deriveFont(Font.BOLD, 12f));
        lbStatus.setPreferredSize(new Dimension(showReadCounts ? 56 : 96, 22));

        RowWidgets widgets = new RowWidgets(row, lbCode, lbName, lbStatus);
        rowsByKey.put(key, widgets);

        if (showReadCounts) {
            applyCount(lbStatus, count);
        } else if (detected) {
            markDetected(widgets);
        } else {
            markPending(widgets);
        }

        row.add(textCol, BorderLayout.CENTER);
        row.add(lbStatus, BorderLayout.EAST);

        JPanel spaced = new JPanel(new BorderLayout());
        spaced.setOpaque(false);
        spaced.setBorder(WorkflowUiTheme.empty(0, 0, 4, 0));
        spaced.setAlignmentX(Component.LEFT_ALIGNMENT);
        spaced.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                name != null && !name.isEmpty() ? 52 : 42));
        spaced.add(row, BorderLayout.CENTER);
        return spaced;
    }

    private void markDetected(RowWidgets widgets) {
        if (widgets == null) {
            return;
        }
        widgets.lbStatus.setText("DETECTADO");
        widgets.lbStatus.setForeground(WorkflowUiTheme.MONITOR_VALUE);
        widgets.lbStatus.setToolTipText("Código identificado");
        widgets.lbCode.setForeground(WorkflowUiTheme.MONITOR_TEXT);
    }

    private void markPending(RowWidgets widgets) {
        if (widgets == null) {
            return;
        }
        widgets.lbStatus.setText("PENDENTE");
        widgets.lbStatus.setForeground(WorkflowUiTheme.MONITOR_CAPTION);
        widgets.lbStatus.setToolTipText("Aguardando leitura deste código");
        widgets.lbCode.setForeground(WorkflowUiTheme.MONITOR_CAPTION);
    }

    private void applyCount(JLabel label, int count) {
        if (label == null) {
            return;
        }
        label.setText(count + "x");
        label.setForeground(count > 1 ? WorkflowUiTheme.MONITOR_ALERT : WorkflowUiTheme.MONITOR_VALUE);
        label.setToolTipText(count > 1
                ? "Tag detectada " + count + " vezes"
                : "Tag detectada 1 vez");
    }

    private static String normalizeKey(String code) {
        if (code == null) {
            return "";
        }
        return code.trim().toUpperCase(Locale.ROOT);
    }
}
