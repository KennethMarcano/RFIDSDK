package com.peripheral.app;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.table.JTableHeader;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;

public final class WorkflowUiTheme {

    public static final Color BG_PAGE = new Color(0xED, 0xF3, 0xFC);
    public static final Color BG_CARD = new Color(0xFF, 0xFF, 0xFF);
    public static final Color BG_CARD_HIGHLIGHT = new Color(0xCC, 0xD5, 0xE2);
    public static final Color BORDER = new Color(0xCC, 0xD5, 0xE2);
    public static final Color BORDER_FOCUS = new Color(0x3C, 0x53, 0x77);

    public static final Color TEXT_PRIMARY = new Color(0x25, 0x2F, 0x3D);
    public static final Color TEXT_SECONDARY = new Color(0x4F, 0x5E, 0x70);
    public static final Color TEXT_MUTED = new Color(0x80, 0x8E, 0xA0);
    public static final Color TEXT_ON_ACCENT = new Color(0x00, 0x00, 0x00);

    public static final Color ACCENT = new Color(0xFF, 0xBB, 0x00);
    public static final Color ACCENT_HOVER = new Color(0xE6, 0xA8, 0x00);
    public static final Color SUCCESS = new Color(0x3C, 0x53, 0x77);
    public static final Color WARNING = new Color(0xFF, 0xBB, 0x00);
    public static final Color DANGER = new Color(0x25, 0x2F, 0x3D);

    public static final Color BADGE_BG = new Color(0xCC, 0xD5, 0xE2);
    public static final Color BADGE_TEXT = new Color(0x25, 0x2F, 0x3D);
    public static final Color CHIP_BG = new Color(0xED, 0xF3, 0xFC);
    public static final Color CHIP_BORDER = new Color(0xCC, 0xD5, 0xE2);
    public static final Color PILL_BG = new Color(0xED, 0xF3, 0xFC);
    public static final Color PILL_BORDER = new Color(0x80, 0x8E, 0xA0);
    public static final Color PILL_DISABLED = new Color(0xCC, 0xD5, 0xE2);

    public static final Color MONITOR_BG = new Color(0x25, 0x2F, 0x3D);
    public static final Color MONITOR_BORDER = new Color(0x3C, 0x53, 0x77);
    public static final Color MONITOR_ROW_BG = new Color(0x3C, 0x53, 0x77);
    public static final Color MONITOR_CAPTION = new Color(0x80, 0x8E, 0xA0);
    public static final Color MONITOR_VALUE = new Color(0xFF, 0xBB, 0x00);
    public static final Color MONITOR_ALERT = new Color(0xFF, 0xBB, 0x00);
    public static final Color MONITOR_TEXT = new Color(0xED, 0xF3, 0xFC);

    public static final int RADIUS_CARD = 12;
    public static final int RADIUS_PILL = 16;
    public static final int RADIUS_CHIP = 8;

    /** Resolução alvo: display oficial de 7" do Raspberry Pi. */
    public static final int TARGET_SCREEN_WIDTH = 800;
    public static final int TARGET_SCREEN_HEIGHT = 480;
    public static final int MIN_SCREEN_WIDTH = 640;
    public static final int MIN_SCREEN_HEIGHT = 400;

    /** Altura mínima de alvo tocável com o dedo. */
    public static final int TOUCH_HEIGHT = 42;
    public static final int TOUCH_HEIGHT_SM = 36;
    public static final int TOUCH_MIN_WIDTH = 104;

    private WorkflowUiTheme() {
    }

    public static void install() {
        UIManager.put("Panel.background", BG_PAGE);
        UIManager.put("OptionPane.background", BG_CARD);
        UIManager.put("TabbedPane.background", BG_PAGE);
        UIManager.put("TabbedPane.contentAreaColor", BG_PAGE);
        UIManager.put("TabbedPane.selected", BG_CARD);
        UIManager.put("TabbedPane.tabInsets", new Insets(9, 18, 9, 18));
        UIManager.put("TabbedPane.tabAreaInsets", new Insets(0, 2, 0, 2));
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
        UIManager.put("ScrollBar.width", 16);
    }

    /** Área útil da tela, com fallback seguro para o alvo de 7". */
    public static Rectangle availableScreenBounds() {
        try {
            GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice().getDefaultConfiguration();
            Rectangle bounds = gc.getBounds();
            Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(gc);
            int width = bounds.width - insets.left - insets.right;
            int height = bounds.height - insets.top - insets.bottom;
            if (width <= 0 || height <= 0) {
                return new Rectangle(0, 0, TARGET_SCREEN_WIDTH, TARGET_SCREEN_HEIGHT);
            }
            return new Rectangle(bounds.x + insets.left, bounds.y + insets.top, width, height);
        } catch (RuntimeException e) {
            return new Rectangle(0, 0, TARGET_SCREEN_WIDTH, TARGET_SCREEN_HEIGHT);
        }
    }

    /** Área física completa da tela, incluindo o espaço da barra de tarefas. */
    public static Rectangle physicalScreenBounds() {
        try {
            return GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice().getDefaultConfiguration().getBounds();
        } catch (RuntimeException e) {
            return new Rectangle(0, 0, TARGET_SCREEN_WIDTH, TARGET_SCREEN_HEIGHT);
        }
    }

    /**
     * Tela cheia sem bordas. Ligada por padrão em telas pequenas (Raspberry Pi 7") e
     * desligada em monitores grandes para permitir testar o layout alvo em janela.
     * Override: {@code -Drfidsdk.ui.fullscreen=true|false}
     */
    public static boolean isFullScreenEnabled() {
        String prop = System.getProperty("rfidsdk.ui.fullscreen");
        if (prop != null && !prop.trim().isEmpty()) {
            return !"false".equalsIgnoreCase(prop.trim());
        }
        Rectangle bounds = availableScreenBounds();
        return bounds.width <= 1280 && bounds.height <= 800;
    }

    /**
     * Ocupa a tela inteira sem decoração no dispositivo; em monitores grandes mantém uma
     * janela do tamanho alvo para que o layout seja testado como no Raspberry Pi.
     */
    public static void applyTouchScreenSize(Window window) {
        window.setMinimumSize(new Dimension(MIN_SCREEN_WIDTH, MIN_SCREEN_HEIGHT));
        if (isFullScreenEnabled()) {
            applyFullScreen(window);
        } else {
            window.setSize(TARGET_SCREEN_WIDTH, TARGET_SCREEN_HEIGHT);
            window.setLocationRelativeTo(null);
        }
    }

    /** Remove a decoração (só é possível antes da janela existir na tela) e cobre o display. */
    public static void applyFullScreen(Window window) {
        try {
            if (!window.isDisplayable()) {
                if (window instanceof Frame) {
                    ((Frame) window).setUndecorated(true);
                } else if (window instanceof Dialog) {
                    ((Dialog) window).setUndecorated(true);
                }
            }
        } catch (IllegalComponentStateException ignored) {
            // janela já exibida: mantém a decoração e apenas maximiza
        }
        window.setBounds(physicalScreenBounds());
        if (window instanceof Frame) {
            ((Frame) window).setExtendedState(Frame.MAXIMIZED_BOTH);
        }
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
        tabs.setFont(tabs.getFont().deriveFont(Font.BOLD, 13f));
        tabs.setFocusable(false);
    }

    public static void styleTable(JTable table) {
        table.setBackground(BG_CARD);
        table.setForeground(TEXT_PRIMARY);
        table.setRowHeight(34);
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

    /** Envolve conteúdo com rolagem vertical e largura adaptável ao viewport (sem scroll horizontal). */
    public static JScrollPane wrapVerticalScroll(JComponent content) {
        content.setOpaque(false);
        JScrollPane scroll = new JScrollPane(new ScrollableColumnPanel(content));
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(BG_PAGE);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    /** Reduz a janela ao tamanho da tela disponível, mantendo-a centralizada no dono. */
    public static void clampToScreen(Window window, Window owner) {
        Rectangle bounds = availableScreenBounds();
        Dimension size = window.getSize();
        window.setSize(
                Math.min(size.width, bounds.width - 16),
                Math.min(size.height, bounds.height - 16));
        window.setLocationRelativeTo(owner);
    }

    public static JLabel formLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(fontMeta(label));
        label.setForeground(TEXT_SECONDARY);
        return label;
    }

    public static JPanel formRow(Component... items) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 3));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        for (Component item : items) {
            row.add(item);
        }
        return row;
    }

    public static void styleCompactTextField(JTextField field, int columns) {
        field.setColumns(columns);
        field.setFont(field.getFont().deriveFont(Font.PLAIN, 14f));
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                empty(8, 10, 8, 10)));
        Dimension pref = field.getPreferredSize();
        int height = Math.max(pref.height, TOUCH_HEIGHT_SM);
        int width = Math.min(Math.max(pref.width, 88), 180);
        field.setPreferredSize(new Dimension(width, height));
        field.setMinimumSize(new Dimension(88, height));
        field.setMaximumSize(new Dimension(180, height));
    }

    public static void styleCompactSpinner(JSpinner spinner) {
        spinner.setFont(spinner.getFont().deriveFont(Font.PLAIN, 14f));
        JComponent editor = spinner.getEditor();
        if (editor instanceof JSpinner.DefaultEditor) {
            JTextField textField = ((JSpinner.DefaultEditor) editor).getTextField();
            textField.setFont(textField.getFont().deriveFont(Font.PLAIN, 14f));
            textField.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(BORDER),
                    empty(6, 8, 6, 8)));
            textField.setColumns(5);
        }
        Dimension pref = spinner.getPreferredSize();
        int height = Math.max(pref.height, TOUCH_HEIGHT_SM);
        spinner.setPreferredSize(new Dimension(104, height));
        spinner.setMinimumSize(new Dimension(96, height));
        spinner.setMaximumSize(new Dimension(124, height));
    }

    public static void styleFormCombo(JComboBox<?> combo, int minWidth, int maxWidth) {
        combo.setFont(combo.getFont().deriveFont(Font.PLAIN, 13f));
        Dimension pref = combo.getPreferredSize();
        int height = Math.max(pref.height, TOUCH_HEIGHT_SM);
        int width = Math.min(Math.max(pref.width, minWidth), maxWidth);
        combo.setPreferredSize(new Dimension(width, height));
        combo.setMinimumSize(new Dimension(minWidth, height));
        combo.setMaximumSize(new Dimension(maxWidth, height));
    }

    /** Checkbox com alvo de toque maior (o rótulo também é clicável). */
    public static void styleTouchCheckBox(JCheckBox checkBox) {
        checkBox.setOpaque(false);
        checkBox.setForeground(TEXT_PRIMARY);
        checkBox.setFont(checkBox.getFont().deriveFont(Font.PLAIN, 13f));
        checkBox.setIconTextGap(10);
        checkBox.setFocusPainted(false);
        checkBox.setBorder(empty(6, 2, 6, 8));
    }

    public static JPanel createInsetGroup(String title, Component content) {
        JPanel group = new JPanel(new BorderLayout(0, 6));
        group.setOpaque(true);
        group.setBackground(CHIP_BG);
        group.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(CHIP_BORDER),
                empty(8, 10, 8, 10)));

        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(fontChip(titleLabel).deriveFont(Font.BOLD));
        titleLabel.setForeground(TEXT_SECONDARY);
        group.add(titleLabel, BorderLayout.NORTH);
        group.add(content, BorderLayout.CENTER);
        return group;
    }

    public static JPanel createStatusStrip() {
        JPanel strip = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 3));
        strip.setOpaque(true);
        strip.setBackground(BG_CARD_HIGHLIGHT);
        strip.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_FOCUS),
                empty(6, 10, 6, 10)));
        strip.setAlignmentX(Component.LEFT_ALIGNMENT);
        return strip;
    }

    public static void styleStatusPill(JLabel label, Color background, Color foreground) {
        label.setOpaque(true);
        label.setBackground(background);
        label.setForeground(foreground);
        label.setFont(fontChip(label));
        label.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(blend(foreground, 0.25f)),
                empty(3, 10, 3, 10)));
    }

    public static void styleMutedCaption(JLabel label) {
        label.setFont(fontChip(label).deriveFont(Font.BOLD));
        label.setForeground(TEXT_MUTED);
    }

    public static void prepareBoxSection(JComponent section) {
        section.setAlignmentX(Component.LEFT_ALIGNMENT);
    }

    /** Duas seções lado a lado (50/50); empilha verticalmente em telas estreitas. */
    public static JPanel createResponsiveColumns(JComponent left, JComponent right) {
        return new ResponsiveColumnsPanel(left, right);
    }

    private static final class ResponsiveColumnsPanel extends JPanel {

        private static final int GAP = 12;
        private static final int MIN_COLUMN_WIDTH = 300;
        private static final int DEFAULT_WIDTH = 720;

        private final JComponent left;
        private final JComponent right;

        ResponsiveColumnsPanel(JComponent left, JComponent right) {
            this.left = left;
            this.right = right;
            setOpaque(false);
            setLayout(null);
            setAlignmentX(Component.LEFT_ALIGNMENT);
            prepareBoxSection(left);
            prepareBoxSection(right);
            add(left);
            add(right);
            addComponentListener(new java.awt.event.ComponentAdapter() {
                @Override
                public void componentResized(java.awt.event.ComponentEvent e) {
                    revalidate();
                    repaint();
                }
            });
        }

        private boolean isStacked(int width) {
            return width < MIN_COLUMN_WIDTH * 2 + GAP;
        }

        @Override
        public void doLayout() {
            int width = Math.max(getWidth(), 0);
            if (width == 0) {
                return;
            }
            if (isStacked(width)) {
                int y = 0;
                int leftHeight = preferredHeightAtWidth(left, width);
                left.setBounds(0, y, width, leftHeight);
                y += leftHeight + GAP;
                int rightHeight = preferredHeightAtWidth(right, width);
                right.setBounds(0, y, width, rightHeight);
            } else {
                int colWidth = (width - GAP) / 2;
                int leftHeight = preferredHeightAtWidth(left, colWidth);
                int rightHeight = preferredHeightAtWidth(right, colWidth);
                int rowHeight = Math.max(leftHeight, rightHeight);
                left.setBounds(0, 0, colWidth, rowHeight);
                right.setBounds(colWidth + GAP, 0, colWidth, rowHeight);
            }
        }

        @Override
        public Dimension getPreferredSize() {
            return sizeForWidth(resolveLayoutWidth());
        }

        @Override
        public Dimension getMinimumSize() {
            return sizeForWidth(MIN_COLUMN_WIDTH);
        }

        private int resolveLayoutWidth() {
            int width = getWidth();
            if (width > 0) {
                return width;
            }
            Container parent = getParent();
            while (parent != null) {
                if (parent.getWidth() > 0) {
                    return parent.getWidth();
                }
                parent = parent.getParent();
            }
            return DEFAULT_WIDTH;
        }

        private Dimension sizeForWidth(int width) {
            int effectiveWidth = Math.max(width, MIN_COLUMN_WIDTH);
            if (isStacked(effectiveWidth)) {
                int leftHeight = preferredHeightAtWidth(left, effectiveWidth);
                int rightHeight = preferredHeightAtWidth(right, effectiveWidth);
                return new Dimension(effectiveWidth, leftHeight + GAP + rightHeight);
            }
            int colWidth = (effectiveWidth - GAP) / 2;
            int rowHeight = Math.max(
                    preferredHeightAtWidth(left, colWidth),
                    preferredHeightAtWidth(right, colWidth));
            return new Dimension(effectiveWidth, rowHeight);
        }

        private static int preferredHeightAtWidth(JComponent component, int width) {
            if (width <= 0) {
                return component.getPreferredSize().height;
            }
            Dimension current = component.getSize();
            component.setSize(width, Short.MAX_VALUE);
            int height = component.getPreferredSize().height;
            component.setSize(current);
            return height;
        }
    }

    private static Color blend(Color base, float alphaTowardWhite) {
        int r = (int) (base.getRed() + (255 - base.getRed()) * alphaTowardWhite);
        int g = (int) (base.getGreen() + (255 - base.getGreen()) * alphaTowardWhite);
        int b = (int) (base.getBlue() + (255 - base.getBlue()) * alphaTowardWhite);
        return new Color(r, g, b);
    }

    private static final class ScrollableColumnPanel extends JPanel implements Scrollable {

        ScrollableColumnPanel(JComponent content) {
            setLayout(new BorderLayout());
            setOpaque(false);
            add(content, BorderLayout.NORTH);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            if (orientation == SwingConstants.VERTICAL) {
                return Math.max(visibleRect.height - 48, 64);
            }
            return Math.max(visibleRect.width - 48, 64);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
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
        section.setBorder(empty(0, 0, 8, 0));

        JLabel titleLabel = createSectionTitle(title);
        titleLabel.setBorder(empty(9, 12, 5, 12));

        JPanel body = new JPanel(new BorderLayout());
        body.setOpaque(false);
        body.setBorder(empty(0, 12, 10, 12));
        body.add(content, BorderLayout.CENTER);

        section.add(titleLabel, BorderLayout.NORTH);
        section.add(body, BorderLayout.CENTER);
        return section;
    }

    public static JPanel createHeader(String title, String subtitle) {
        return createHeader(title, subtitle, null);
    }

    /** Cabeçalho compacto: título, subtítulo, logo e (opcional) ações à direita. */
    public static JPanel createHeader(String title, String subtitle, Component actions) {
        JPanel header = new JPanel(new BorderLayout(10, 0));
        header.setOpaque(false);
        header.setBorder(empty(6, 10, 6, 10));

        JPanel titles = new JPanel();
        titles.setOpaque(false);
        titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));

        JLabel lbTitle = new JLabel(title);
        lbTitle.setFont(fontTitle(lbTitle));
        lbTitle.setForeground(TEXT_PRIMARY);
        lbTitle.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel lbSubtitle = new JLabel(subtitle);
        lbSubtitle.setFont(fontChip(lbSubtitle));
        lbSubtitle.setForeground(TEXT_SECONDARY);
        lbSubtitle.setAlignmentX(Component.LEFT_ALIGNMENT);

        titles.add(lbTitle);
        titles.add(lbSubtitle);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);
        if (actions != null) {
            right.add(actions);
        }
        right.add(BrandingAssets.createEshipLogoLabel(34));

        header.add(titles, BorderLayout.CENTER);
        header.add(right, BorderLayout.EAST);
        return header;
    }

    public static void setStatusColor(JLabel label, Color color) {
        if (label != null && color != null) {
            label.setForeground(color);
        }
    }

    public static Font fontTitle(Component c) {
        return c.getFont().deriveFont(Font.BOLD, 15f);
    }

    public static Font fontStatus(Component c) {
        return c.getFont().deriveFont(Font.PLAIN, 13f);
    }

    public static Font fontWeight(Component c) {
        return new Font(Font.MONOSPACED, Font.BOLD, 20);
    }

    /** Fonte monoespaçada usada nos códigos das tags RFID. */
    public static Font fontTagCode(Component c) {
        return new Font(Font.MONOSPACED, Font.BOLD, 13);
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
