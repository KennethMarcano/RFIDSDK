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
 * Em telas pequenas (7"), os produtos ficam em grade horizontal com wrap.
 */
public class RfidTagMonitorPanel extends JPanel {

    private static final int TILE_WIDTH = 168;
    private static final int TILE_HEIGHT = 58;
    private static final int TILE_GAP = 6;

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
    private final JPanel tiles = new JPanel(new WrapLayout(FlowLayout.LEFT, TILE_GAP, TILE_GAP));

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
                WorkflowUiTheme.empty(8, 10, 8, 10)));
        setAlignmentX(Component.LEFT_ALIGNMENT);

        lbCaption = new JLabel(caption);
        lbCaption.setFont(lbCaption.getFont().deriveFont(Font.BOLD, 12f));
        lbCaption.setForeground(WorkflowUiTheme.MONITOR_CAPTION);

        tiles.setOpaque(true);
        tiles.setBackground(WorkflowUiTheme.MONITOR_BG);

        lbHint.setFont(lbHint.getFont().deriveFont(Font.PLAIN, 11f));
        lbHint.setForeground(WorkflowUiTheme.MONITOR_CAPTION);
        lbHint.setBorder(WorkflowUiTheme.empty(2, 2, 4, 2));

        JPanel listHost = new JPanel(new BorderLayout(0, 2));
        listHost.setOpaque(true);
        listHost.setBackground(WorkflowUiTheme.MONITOR_BG);
        listHost.add(lbHint, BorderLayout.NORTH);
        listHost.add(tiles, BorderLayout.CENTER);

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
        valueLabel.setFont(valueLabel.getFont().deriveFont(Font.BOLD, 18f));
        valueLabel.setForeground(Color.WHITE);

        JLabel lbCounterCaption = new JLabel(caption);
        lbCounterCaption.setFont(lbCounterCaption.getFont().deriveFont(Font.BOLD, 9f));
        lbCounterCaption.setForeground(WorkflowUiTheme.MONITOR_CAPTION);
        lbCounterCaption.setBorder(WorkflowUiTheme.empty(5, 3, 0, 6));

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
                hideHintIfNeeded();
                if (!rowsByKey.containsKey(normalized)) {
                    tiles.add(createTagTile(normalized, code.trim(), "", true, updated));
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
                hideHintIfNeeded();
                ProductEntry expected = findExpectedByCode(normalized);
                String displayCode = expected != null ? expected.getCode() : code.trim();
                String name = expected != null ? expected.getName() : "";
                tiles.add(createTagTile(rowId, displayCode, name, true, 1));
            } else {
                detectedKeys.add(rowId);
                hideHintIfNeeded();
                RowWidgets existing = rowsByKey.get(rowId);
                if (existing != null) {
                    markDetected(existing);
                }
            }
        }
        tiles.revalidate();
        tiles.repaint();
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
        tiles.removeAll();
        lbHint.setVisible(true);
        rebuildSummaryCounters();
        tiles.revalidate();
        tiles.repaint();
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
        if (text != null && !text.trim().isEmpty()) {
            lbHint.setVisible(true);
        }
    }

    public int getUniqueTagCount() {
        return showReadCounts ? counts.size() : detectedKeys.size();
    }

    public int getTotalReads() {
        return totalReads;
    }

    private void rebuildRowsFromState() {
        tiles.removeAll();
        rowsByKey.clear();
        if (expectedProducts.isEmpty() && detectedKeys.isEmpty()) {
            lbHint.setVisible(true);
            tiles.revalidate();
            tiles.repaint();
            return;
        }
        lbHint.setVisible(false);
        for (ProductEntry entry : expectedProducts) {
            String rowId = normalizeKey(entry.getRowId());
            boolean detected = detectedKeys.contains(rowId);
            tiles.add(createTagTile(rowId, entry.getCode(), entry.getName(), detected, 1));
        }
        for (String key : new ArrayList<>(detectedKeys)) {
            if (rowsByKey.containsKey(key)) {
                continue;
            }
            String display = key;
            Integer count = counts.getOrDefault(key, 1);
            tiles.add(createTagTile(key, display, "Extra", true, count));
        }
        tiles.revalidate();
        tiles.repaint();
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

    private void hideHintIfNeeded() {
        if (lbHint.isVisible() && (!expectedProducts.isEmpty() || !detectedKeys.isEmpty())) {
            lbHint.setVisible(false);
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

    private JPanel createTagTile(String key, String code, String name, boolean detected, int count) {
        JPanel tile = new JPanel(new BorderLayout(4, 0));
        tile.setOpaque(true);
        tile.setBackground(WorkflowUiTheme.MONITOR_ROW_BG);
        tile.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(WorkflowUiTheme.MONITOR_BORDER, 1),
                WorkflowUiTheme.empty(5, 7, 5, 7)));
        Dimension size = new Dimension(TILE_WIDTH, TILE_HEIGHT);
        tile.setPreferredSize(size);
        tile.setMinimumSize(size);
        tile.setMaximumSize(size);

        JLabel lbCode = new JLabel(truncate(code, 14));
        lbCode.setFont(lbCode.getFont().deriveFont(Font.BOLD, 12f));
        lbCode.setForeground(WorkflowUiTheme.MONITOR_TEXT);
        lbCode.setToolTipText("Código: " + code);

        JLabel lbStatus = new JLabel("", SwingConstants.RIGHT);
        lbStatus.setFont(lbStatus.getFont().deriveFont(Font.BOLD, 10f));

        JPanel top = new JPanel(new BorderLayout(4, 0));
        top.setOpaque(false);
        top.add(lbCode, BorderLayout.CENTER);
        top.add(lbStatus, BorderLayout.EAST);

        String displayName = name != null ? name : "";
        JLabel lbName = new JLabel(truncate(displayName, 18));
        lbName.setFont(lbName.getFont().deriveFont(Font.PLAIN, 10f));
        lbName.setForeground(WorkflowUiTheme.MONITOR_CAPTION);
        lbName.setVisible(!displayName.isEmpty());
        if (!displayName.isEmpty()) {
            lbName.setToolTipText(displayName);
        }

        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        top.setAlignmentX(Component.LEFT_ALIGNMENT);
        lbName.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(top);
        if (lbName.isVisible()) {
            body.add(Box.createVerticalStrut(2));
            body.add(lbName);
        }

        tile.add(body, BorderLayout.CENTER);

        RowWidgets widgets = new RowWidgets(tile, lbCode, lbName, lbStatus);
        rowsByKey.put(key, widgets);

        if (showReadCounts) {
            applyCount(lbStatus, count);
        } else if (detected) {
            markDetected(widgets);
        } else {
            markPending(widgets);
        }
        return tile;
    }

    private void markDetected(RowWidgets widgets) {
        if (widgets == null) {
            return;
        }
        widgets.lbStatus.setText("OK");
        widgets.lbStatus.setForeground(WorkflowUiTheme.MONITOR_VALUE);
        widgets.lbStatus.setToolTipText("Código identificado");
        widgets.lbCode.setForeground(WorkflowUiTheme.MONITOR_TEXT);
        widgets.root.setBackground(new Color(0x2F, 0x45, 0x63));
        widgets.root.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(WorkflowUiTheme.MONITOR_VALUE, 1),
                WorkflowUiTheme.empty(5, 7, 5, 7)));
    }

    private void markPending(RowWidgets widgets) {
        if (widgets == null) {
            return;
        }
        widgets.lbStatus.setText("...");
        widgets.lbStatus.setForeground(WorkflowUiTheme.MONITOR_CAPTION);
        widgets.lbStatus.setToolTipText("Aguardando leitura deste código");
        widgets.lbCode.setForeground(WorkflowUiTheme.MONITOR_CAPTION);
        widgets.root.setBackground(WorkflowUiTheme.MONITOR_ROW_BG);
        widgets.root.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(WorkflowUiTheme.MONITOR_BORDER, 1),
                WorkflowUiTheme.empty(5, 7, 5, 7)));
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

    private static String truncate(String value, int maxChars) {
        if (value == null || value.isEmpty() || value.length() <= maxChars) {
            return value != null ? value : "";
        }
        return value.substring(0, Math.max(1, maxChars - 1)) + "…";
    }

    private static String normalizeKey(String code) {
        if (code == null) {
            return "";
        }
        return code.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * FlowLayout que quebra linha conforme a largura disponível —
     * essencial para caber vários produtos numa tela de 7".
     */
    private static final class WrapLayout extends FlowLayout {
        WrapLayout(int align, int hgap, int vgap) {
            super(align, hgap, vgap);
        }

        @Override
        public Dimension preferredLayoutSize(Container target) {
            return layoutSize(target, true);
        }

        @Override
        public Dimension minimumLayoutSize(Container target) {
            Dimension minimum = layoutSize(target, false);
            minimum.width -= (getHgap() + 1);
            return minimum;
        }

        private Dimension layoutSize(Container target, boolean preferred) {
            synchronized (target.getTreeLock()) {
                int targetWidth = target.getWidth();
                Container parent = target.getParent();
                if (targetWidth <= 0 && parent != null) {
                    targetWidth = parent.getWidth();
                }
                if (targetWidth <= 0) {
                    targetWidth = WorkflowUiTheme.TARGET_SCREEN_WIDTH;
                }

                int hgap = getHgap();
                int vgap = getVgap();
                Insets insets = target.getInsets();
                int maxWidth = targetWidth - insets.left - insets.right;

                int x = 0;
                int y = insets.top;
                int rowHeight = 0;
                int reqWidth = 0;

                for (Component comp : target.getComponents()) {
                    if (!comp.isVisible()) {
                        continue;
                    }
                    Dimension d = preferred ? comp.getPreferredSize() : comp.getMinimumSize();
                    if (x == 0 || x + d.width <= maxWidth) {
                        if (x > 0) {
                            x += hgap;
                        }
                        x += d.width;
                        rowHeight = Math.max(rowHeight, d.height);
                    } else {
                        y += vgap + rowHeight;
                        x = d.width;
                        rowHeight = d.height;
                    }
                    reqWidth = Math.max(reqWidth, x);
                }
                y += rowHeight + insets.bottom;
                return new Dimension(reqWidth + insets.left + insets.right, y);
            }
        }
    }
}
