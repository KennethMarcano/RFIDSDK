package com.peripheral.app;

import javax.swing.*;
import java.awt.*;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Monitor de leitura RFID no mesmo estilo do monitor da balança: mostra cada tag
 * detectada e quantas vezes ela foi lida durante a sessão de leitura.
 */
public class RfidTagMonitorPanel extends JPanel {

    private final Map<String, Integer> counts = new LinkedHashMap<>();
    private final Map<String, JLabel> countLabels = new LinkedHashMap<>();

    private final JLabel lbCaption;
    private final JLabel lbUniqueValue = new JLabel("0");
    private final JLabel lbTotalValue = new JLabel("0");
    private final JLabel lbHint = new JLabel("Aguardando leitura das tags...");
    private final JPanel rows = new JPanel();

    private int totalReads;

    public RfidTagMonitorPanel(String caption) {
        super(new BorderLayout(0, 6));
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
    }

    private JPanel buildSummaryBar() {
        JPanel counters = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        counters.setOpaque(false);
        counters.add(buildCounter(lbUniqueValue, "TAGS"));
        counters.add(buildCounter(lbTotalValue, "LEITURAS"));

        JPanel bar = new JPanel(new BorderLayout(8, 0));
        bar.setOpaque(false);
        bar.add(lbCaption, BorderLayout.WEST);
        bar.add(counters, BorderLayout.EAST);
        return bar;
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

    /** Registra mais uma leitura da tag informada, somando às repetições anteriores. */
    public void registerTag(String code) {
        if (code == null) {
            return;
        }
        String key = code.trim();
        if (key.isEmpty()) {
            return;
        }
        totalReads++;
        Integer previous = counts.get(key);
        int updated = previous == null ? 1 : previous + 1;
        counts.put(key, updated);

        if (previous == null) {
            if (counts.size() == 1) {
                rows.remove(lbHint);
            }
            rows.add(createTagRow(key, updated));
            rows.revalidate();
            rows.repaint();
        } else {
            applyCount(countLabels.get(key), updated);
        }
        updateSummary();
    }

    /** Limpa as tags e os contadores. */
    public void reset() {
        counts.clear();
        countLabels.clear();
        totalReads = 0;
        rows.removeAll();
        rows.add(lbHint);
        rows.revalidate();
        rows.repaint();
        updateSummary();
    }

    public void setHint(String text) {
        lbHint.setText(text);
    }

    public int getUniqueTagCount() {
        return counts.size();
    }

    public int getTotalReads() {
        return totalReads;
    }

    private void updateSummary() {
        lbUniqueValue.setText(String.valueOf(counts.size()));
        lbTotalValue.setText(String.valueOf(totalReads));
        lbUniqueValue.setForeground(counts.isEmpty() ? Color.WHITE : WorkflowUiTheme.MONITOR_VALUE);
    }

    private JPanel createTagRow(String code, int count) {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(true);
        row.setBackground(WorkflowUiTheme.MONITOR_ROW_BG);
        row.setBorder(WorkflowUiTheme.empty(6, 10, 6, 10));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));

        JLabel lbCode = new JLabel(code);
        lbCode.setFont(WorkflowUiTheme.fontTagCode(lbCode));
        lbCode.setForeground(WorkflowUiTheme.MONITOR_TEXT);
        lbCode.setToolTipText(code);

        JLabel lbCount = new JLabel("", SwingConstants.RIGHT);
        lbCount.setFont(lbCount.getFont().deriveFont(Font.BOLD, 13f));
        lbCount.setPreferredSize(new Dimension(56, 20));
        applyCount(lbCount, count);
        countLabels.put(code, lbCount);

        row.add(lbCode, BorderLayout.CENTER);
        row.add(lbCount, BorderLayout.EAST);

        JPanel spaced = new JPanel(new BorderLayout());
        spaced.setOpaque(false);
        spaced.setBorder(WorkflowUiTheme.empty(0, 0, 3, 0));
        spaced.setAlignmentX(Component.LEFT_ALIGNMENT);
        spaced.setMaximumSize(new Dimension(Integer.MAX_VALUE, 37));
        spaced.add(row, BorderLayout.CENTER);
        return spaced;
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
}
