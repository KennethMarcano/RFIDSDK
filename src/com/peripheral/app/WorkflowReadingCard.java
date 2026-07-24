package com.peripheral.app;

import com.peripheral.scale.ScaleWeightFormat;
import com.peripheral.workflow.WorkflowReadingRecord;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

public class WorkflowReadingCard extends JPanel {

    public interface ActionListener {
        void onViewPhoto(WorkflowReadingRecord record);

        void onViewLabel(WorkflowReadingRecord record);
    }

    private final WorkflowReadingRecord record;
    private final boolean photoEnabled;
    private final boolean labelEnabled;
    private boolean highlight;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("dd/MM/yyyy  HH:mm:ss");

    private PillButton btnPhoto;
    private PillButton btnLabel;

    public WorkflowReadingCard(WorkflowReadingRecord record,
                               boolean photoEnabled,
                               boolean labelEnabled,
                               boolean highlight,
                               ActionListener listener) {
        this.record = record;
        this.photoEnabled = photoEnabled;
        this.labelEnabled = labelEnabled;
        this.highlight = highlight;
        setOpaque(false);
        setLayout(new BorderLayout(12, 0));
        setBorder(WorkflowUiTheme.empty(4, 4, 4, 4));
        setMaximumSize(new Dimension(Integer.MAX_VALUE, Short.MAX_VALUE));
        buildContent(listener);
    }

    public WorkflowReadingRecord getRecord() {
        return record;
    }

    public void setHighlight(boolean highlight) {
        this.highlight = highlight;
        repaint();
    }

    private void buildContent(ActionListener listener) {
        JPanel indexBadge = createIndexBadge();
        add(indexBadge, BorderLayout.WEST);

        JPanel center = new JPanel();
        center.setOpaque(false);
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));

        JPanel weightRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        weightRow.setOpaque(false);
        weightRow.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel lbWeight = new JLabel(ScaleWeightFormat.formatGrams(record.getWeightKg()));
        lbWeight.setFont(WorkflowUiTheme.fontWeight(lbWeight));
        lbWeight.setForeground(WorkflowUiTheme.TEXT_PRIMARY);

        JLabel lbUnit = new JLabel(ScaleWeightFormat.UNIT);
        lbUnit.setFont(WorkflowUiTheme.fontWeightUnit(lbUnit));
        lbUnit.setForeground(WorkflowUiTheme.TEXT_SECONDARY);
        lbUnit.setBorder(WorkflowUiTheme.empty(6, 0, 0, 0));

        weightRow.add(lbWeight);
        weightRow.add(lbUnit);

        JLabel lbTime = new JLabel(timeFormat.format(new Date(record.getTimestampMs())));
        lbTime.setFont(WorkflowUiTheme.fontMeta(lbTime));
        lbTime.setForeground(WorkflowUiTheme.TEXT_SECONDARY);
        lbTime.setAlignmentX(Component.LEFT_ALIGNMENT);
        lbTime.setBorder(WorkflowUiTheme.empty(2, 0, 6, 0));

        JPanel chips = createProductChips(record.getTagCodes());
        chips.setAlignmentX(Component.LEFT_ALIGNMENT);

        center.add(weightRow);
        center.add(lbTime);
        center.add(chips);
        add(center, BorderLayout.CENTER);

        JPanel actions = new JPanel();
        actions.setOpaque(false);
        actions.setLayout(new BoxLayout(actions, BoxLayout.Y_AXIS));

        boolean photoAvailable = photoEnabled && record.hasPhoto()
                && new File(record.getPhotoPath()).isFile();
        boolean labelAvailable = labelEnabled && record.hasLabel()
                && new File(record.getLabelPdfPath()).isFile();

        btnPhoto = new PillButton("Ver foto", photoAvailable, "\uD83D\uDCF7");
        btnLabel = new PillButton("Ver etiqueta", labelAvailable, "\uD83C\uDFF7");

        if (photoAvailable) {
            btnPhoto.addAction(() -> listener.onViewPhoto(record));
        }
        if (labelAvailable) {
            btnLabel.addAction(() -> listener.onViewLabel(record));
        }

        actions.add(btnPhoto);
        actions.add(Box.createVerticalStrut(6));
        actions.add(btnLabel);
        add(actions, BorderLayout.EAST);
    }

    private JPanel createIndexBadge() {
        JPanel badge = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(WorkflowUiTheme.BADGE_BG);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        badge.setOpaque(false);
        badge.setPreferredSize(new Dimension(44, 44));
        badge.setMinimumSize(new Dimension(44, 44));
        badge.setLayout(new GridBagLayout());

        JLabel lbIndex = new JLabel("#" + record.getIndex());
        lbIndex.setFont(lbIndex.getFont().deriveFont(Font.BOLD, 13f));
        lbIndex.setForeground(WorkflowUiTheme.BADGE_TEXT);
        badge.add(lbIndex);
        return badge;
    }

    private JPanel createProductChips(List<String> tagCodes) {
        JPanel flow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        flow.setOpaque(false);

        if (tagCodes == null || tagCodes.isEmpty()) {
            JLabel empty = new JLabel("Nenhum produto identificado");
            empty.setFont(WorkflowUiTheme.fontMeta(empty));
            empty.setForeground(WorkflowUiTheme.TEXT_MUTED);
            flow.add(empty);
            return flow;
        }

        for (String code : tagCodes) {
            flow.add(createChip(code));
        }
        return flow;
    }

    private JComponent createChip(String text) {
        JLabel chip = new JLabel(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(WorkflowUiTheme.CHIP_BG);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), WorkflowUiTheme.RADIUS_CHIP, WorkflowUiTheme.RADIUS_CHIP);
                g2.setColor(WorkflowUiTheme.CHIP_BORDER);
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1,
                        WorkflowUiTheme.RADIUS_CHIP, WorkflowUiTheme.RADIUS_CHIP);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        chip.setOpaque(false);
        chip.setFont(WorkflowUiTheme.fontChip(chip));
        chip.setForeground(WorkflowUiTheme.TEXT_PRIMARY);
        chip.setBorder(WorkflowUiTheme.empty(3, 10, 3, 10));
        return chip;
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int w = getWidth();
        int h = getHeight();
        int shadowOffset = 2;

        g2.setColor(new Color(0, 0, 0, 12));
        g2.fill(new RoundRectangle2D.Float(shadowOffset, shadowOffset,
                w - shadowOffset, h - shadowOffset,
                WorkflowUiTheme.RADIUS_CARD, WorkflowUiTheme.RADIUS_CARD));

        g2.setColor(highlight ? WorkflowUiTheme.BG_CARD_HIGHLIGHT : WorkflowUiTheme.BG_CARD);
        g2.fill(new RoundRectangle2D.Float(0, 0, w - shadowOffset, h - shadowOffset,
                WorkflowUiTheme.RADIUS_CARD, WorkflowUiTheme.RADIUS_CARD));

        g2.setColor(highlight ? WorkflowUiTheme.BORDER_FOCUS : WorkflowUiTheme.BORDER);
        g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - shadowOffset - 1, h - shadowOffset - 1,
                WorkflowUiTheme.RADIUS_CARD, WorkflowUiTheme.RADIUS_CARD));

        if (highlight) {
            g2.setColor(WorkflowUiTheme.ACCENT);
            g2.fillRoundRect(0, 8, 4, h - shadowOffset - 16, 4, 4);
        }

        g2.dispose();
        super.paintComponent(g);
    }

    private static final class PillButton extends JComponent {

        private final String label;
        private final boolean enabled;
        private final String icon;
        private boolean hovered;
        private Runnable action;

        PillButton(String label, boolean enabled, String icon) {
            this.label = label;
            this.enabled = enabled;
            this.icon = icon;
            setOpaque(false);
            setCursor(enabled ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
            setPreferredSize(new Dimension(118, 30));
            setMinimumSize(new Dimension(118, 30));
            setMaximumSize(new Dimension(118, 30));

            if (enabled) {
                addMouseListener(new MouseAdapter() {
                    @Override
                    public void mouseEntered(MouseEvent e) {
                        hovered = true;
                        repaint();
                    }

                    @Override
                    public void mouseExited(MouseEvent e) {
                        hovered = false;
                        repaint();
                    }

                    @Override
                    public void mouseClicked(MouseEvent e) {
                        if (action != null) {
                            action.run();
                        }
                    }
                });
            }
        }

        void addAction(Runnable runnable) {
            this.action = runnable;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();

            if (!enabled) {
                g2.setColor(WorkflowUiTheme.PILL_DISABLED);
                g2.fillRoundRect(0, 0, w, h, WorkflowUiTheme.RADIUS_PILL, WorkflowUiTheme.RADIUS_PILL);
                g2.setColor(WorkflowUiTheme.BORDER);
                g2.drawRoundRect(0, 0, w - 1, h - 1, WorkflowUiTheme.RADIUS_PILL, WorkflowUiTheme.RADIUS_PILL);
                g2.setColor(WorkflowUiTheme.TEXT_MUTED);
            } else if (hovered) {
                g2.setColor(WorkflowUiTheme.ACCENT);
                g2.fillRoundRect(0, 0, w, h, WorkflowUiTheme.RADIUS_PILL, WorkflowUiTheme.RADIUS_PILL);
                g2.setColor(Color.WHITE);
            } else {
                g2.setColor(WorkflowUiTheme.PILL_BG);
                g2.fillRoundRect(0, 0, w, h, WorkflowUiTheme.RADIUS_PILL, WorkflowUiTheme.RADIUS_PILL);
                g2.setColor(WorkflowUiTheme.PILL_BORDER);
                g2.drawRoundRect(0, 0, w - 1, h - 1, WorkflowUiTheme.RADIUS_PILL, WorkflowUiTheme.RADIUS_PILL);
                g2.setColor(WorkflowUiTheme.ACCENT);
            }

            Font font = WorkflowUiTheme.fontPill(this);
            g2.setFont(font);
            FontMetrics fm = g2.getFontMetrics();
            String text = icon + "  " + label;
            int textWidth = fm.stringWidth(text);
            int x = (w - textWidth) / 2;
            int y = (h + fm.getAscent() - fm.getDescent()) / 2;
            g2.drawString(text, x, y);
            g2.dispose();
        }
    }
}
