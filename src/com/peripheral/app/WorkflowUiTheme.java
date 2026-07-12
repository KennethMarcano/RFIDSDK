package com.peripheral.app;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.table.JTableHeader;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;

public final class WorkflowUiTheme {

    public static final Color BG_PAGE = new Color(0xF1, 0xF5, 0xF9);
    public static final Color BG_CARD = Color.WHITE;
    public static final Color BG_CARD_HIGHLIGHT = new Color(0xEF, 0xF6, 0xFF);
    public static final Color BORDER = new Color(0xE2, 0xE8, 0xF0);
    public static final Color BORDER_FOCUS = new Color(0xBF, 0xDB, 0xFE);

    public static final Color TEXT_PRIMARY = new Color(0x0F, 0x17, 0x2A);
    public static final Color TEXT_SECONDARY = new Color(0x64, 0x74, 0x8B);
    public static final Color TEXT_MUTED = new Color(0x94, 0xA3, 0xB8);

    public static final Color ACCENT = new Color(0x25, 0x63, 0xEB);
    public static final Color ACCENT_HOVER = new Color(0x1D, 0x4E, 0xD8);
    public static final Color SUCCESS = new Color(0x05, 0x96, 0x69);
    public static final Color WARNING = new Color(0xD9, 0x77, 0x06);
    public static final Color DANGER = new Color(0xDC, 0x26, 0x26);

    public static final Color BADGE_BG = new Color(0xE0, 0xE7, 0xFF);
    public static final Color BADGE_TEXT = new Color(0x37, 0x41, 0xCF);
    public static final Color CHIP_BG = new Color(0xF8, 0xFA, 0xFC);
    public static final Color CHIP_BORDER = new Color(0xE2, 0xE8, 0xF0);
    public static final Color PILL_BG = new Color(0xF8, 0xFA, 0xFC);
    public static final Color PILL_BORDER = new Color(0xCB, 0xD5, 0xE1);
    public static final Color PILL_DISABLED = new Color(0xE2, 0xE8, 0xF0);

    public static final int RADIUS_CARD = 12;
    public static final int RADIUS_PILL = 16;
    public static final int RADIUS_CHIP = 8;

    private WorkflowUiTheme() {
    }

    public static void install() {
        UIManager.put("Panel.background", BG_PAGE);
        UIManager.put("OptionPane.background", BG_CARD);
        UIManager.put("TabbedPane.background", BG_PAGE);
        UIManager.put("TabbedPane.contentAreaColor", BG_PAGE);
        UIManager.put("TabbedPane.selected", BG_CARD);
        UIManager.put("ScrollPane.background", BG_PAGE);
        UIManager.put("Viewport.background", BG_CARD);
        UIManager.put("Table.background", BG_CARD);
        UIManager.put("Table.foreground", TEXT_PRIMARY);
        UIManager.put("Table.gridColor", BORDER);
        UIManager.put("Table.selectionBackground", BG_CARD_HIGHLIGHT);
        UIManager.put("Table.selectionForeground", TEXT_PRIMARY);
        UIManager.put("TextArea.background", BG_CARD);
        UIManager.put("TextArea.foreground", TEXT_PRIMARY);
        UIManager.put("ComboBox.background", BG_CARD);
        UIManager.put("ComboBox.foreground", TEXT_PRIMARY);
        UIManager.put("Label.foreground", TEXT_PRIMARY);
    }

    public static ThemedButton button(String text, ThemedButton.Variant variant) {
        return new ThemedButton(text, variant);
    }

    public static void styleFrame(JFrame frame) {
        frame.getContentPane().setBackground(BG_PAGE);
    }

    public static void styleDialog(JDialog dialog) {
        dialog.getContentPane().setBackground(BG_PAGE);
    }

    public static void stylePanel(JPanel panel) {
        panel.setBackground(BG_PAGE);
        panel.setOpaque(true);
    }

    public static void styleTabbedPane(JTabbedPane tabs) {
        tabs.setBackground(BG_PAGE);
        tabs.setForeground(TEXT_PRIMARY);
        tabs.setFont(tabs.getFont().deriveFont(Font.BOLD, 12f));
    }

    public static void styleTable(JTable table) {
        table.setBackground(BG_CARD);
        table.setForeground(TEXT_PRIMARY);
        table.setRowHeight(28);
        table.setShowHorizontalLines(true);
        table.setGridColor(BORDER);
        table.setSelectionBackground(BG_CARD_HIGHLIGHT);
        table.setSelectionForeground(TEXT_PRIMARY);
        table.setIntercellSpacing(new Dimension(0, 1));
        JTableHeader header = table.getTableHeader();
        if (header != null) {
            header.setBackground(BG_PAGE);
            header.setForeground(TEXT_SECONDARY);
            header.setFont(header.getFont().deriveFont(Font.BOLD, 11f));
            header.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER));
        }
    }

    public static void styleTextArea(JTextArea area) {
        area.setBackground(BG_CARD);
        area.setForeground(TEXT_PRIMARY);
        area.setCaretColor(ACCENT);
        area.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                empty(6, 8, 6, 8)));
    }

    public static void styleScrollPane(JScrollPane scroll) {
        scroll.setBorder(BorderFactory.createLineBorder(BORDER));
        scroll.getViewport().setBackground(BG_CARD);
    }

    /** Envolve conteúdo vertical excedente com barra de rolagem. */
    public static JScrollPane wrapVerticalScroll(JComponent content) {
        content.setOpaque(false);
        JScrollPane scroll = new JScrollPane(content);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(BG_PAGE);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    public static JLabel createSectionTitle(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 13f));
        label.setForeground(TEXT_PRIMARY);
        return label;
    }

    public static JLabel createHintLabel(String html) {
        JLabel label = new JLabel(html);
        label.setFont(fontMeta(label));
        label.setForeground(TEXT_SECONDARY);
        return label;
    }

    public static JPanel createSection(String title, Component content) {
        JPanel section = new JPanel(new BorderLayout(0, 0)) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(BG_CARD);
                g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), RADIUS_CARD, RADIUS_CARD));
                g2.setColor(BORDER);
                g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1, getHeight() - 1,
                        RADIUS_CARD, RADIUS_CARD));
                g2.dispose();
                super.paintComponent(g);
            }
        };
        section.setOpaque(false);
        section.setBorder(empty(0, 0, 12, 0));

        JLabel titleLabel = createSectionTitle(title);
        titleLabel.setBorder(empty(14, 16, 8, 16));

        JPanel body = new JPanel(new BorderLayout());
        body.setOpaque(false);
        body.setBorder(empty(0, 16, 14, 16));
        body.add(content, BorderLayout.CENTER);

        section.add(titleLabel, BorderLayout.NORTH);
        section.add(body, BorderLayout.CENTER);
        return section;
    }

    public static JPanel createHeader(String title, String subtitle) {
        JPanel header = new JPanel(new BorderLayout(12, 0));
        header.setOpaque(false);
        header.setBorder(empty(4, 12, 12, 12));

        JPanel titles = new JPanel();
        titles.setOpaque(false);
        titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));

        JLabel lbTitle = new JLabel(title);
        lbTitle.setFont(fontTitle(lbTitle));
        lbTitle.setForeground(TEXT_PRIMARY);
        lbTitle.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel lbSubtitle = new JLabel(subtitle);
        lbSubtitle.setFont(fontMeta(lbSubtitle));
        lbSubtitle.setForeground(TEXT_SECONDARY);
        lbSubtitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        lbSubtitle.setBorder(empty(2, 0, 0, 0));

        titles.add(lbTitle);
        titles.add(lbSubtitle);

        JPanel brandRow = new JPanel(new BorderLayout(12, 0));
        brandRow.setOpaque(false);
        brandRow.add(titles, BorderLayout.CENTER);
        brandRow.add(BrandingAssets.createEshipLogoLabel(52), BorderLayout.EAST);
        header.add(brandRow, BorderLayout.CENTER);
        return header;
    }

    public static void setStatusColor(JLabel label, Color color) {
        if (label != null && color != null) {
            label.setForeground(color);
        }
    }

    public static Font fontTitle(Component c) {
        return c.getFont().deriveFont(Font.BOLD, 16f);
    }

    public static Font fontStatus(Component c) {
        return c.getFont().deriveFont(Font.PLAIN, 13.5f);
    }

    public static Font fontWeight(Component c) {
        return new Font(Font.MONOSPACED, Font.BOLD, 22);
    }

    public static Font fontWeightUnit(Component c) {
        return c.getFont().deriveFont(Font.PLAIN, 13f);
    }

    public static Font fontMeta(Component c) {
        return c.getFont().deriveFont(Font.PLAIN, 12f);
    }

    public static Font fontChip(Component c) {
        return c.getFont().deriveFont(Font.PLAIN, 11f);
    }

    public static Font fontPill(Component c) {
        return c.getFont().deriveFont(Font.BOLD, 11.5f);
    }

    public static Border empty(int top, int left, int bottom, int right) {
        return BorderFactory.createEmptyBorder(top, left, bottom, right);
    }

    /** @deprecated use {@link #button(String, ThemedButton.Variant)} */
    @Deprecated
    public static void stylePrimaryButton(JButton button) {
        applyVariant(button, ThemedButton.Variant.PRIMARY);
    }

    /** @deprecated use {@link #button(String, ThemedButton.Variant)} */
    @Deprecated
    public static void styleSecondaryButton(JButton button) {
        applyVariant(button, ThemedButton.Variant.SECONDARY);
    }

    /** @deprecated use {@link #button(String, ThemedButton.Variant)} */
    @Deprecated
    public static void styleSuccessButton(JButton button) {
        applyVariant(button, ThemedButton.Variant.SUCCESS);
    }

    public static void applyVariant(JButton button, ThemedButton.Variant variant) {
        if (button instanceof ThemedButton) {
            ((ThemedButton) button).setVariant(variant);
        }
    }
}
