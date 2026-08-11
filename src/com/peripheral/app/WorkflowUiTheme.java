package com.peripheral.app;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.table.JTableHeader;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.io.File;

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
     * Tela cheia sem bordas. Ligada por padrão em telas de quiosque/touch (até Full HD);
     * em monitores maiores mantém janela 800x480 para testar o layout.
     * Override: {@code -Drfidsdk.ui.fullscreen=true|false}
     */
    public static boolean isFullScreenEnabled() {
        String prop = System.getProperty("rfidsdk.ui.fullscreen");
        if (prop != null && !prop.trim().isEmpty()) {
            return !"false".equalsIgnoreCase(prop.trim());
        }
        Rectangle bounds = availableScreenBounds();
        // 7" Pi (800x480), 10" e Full HD de quiosque entram; só monitores grandes ficam em janela.
        return bounds.width <= 1920 && bounds.height <= 1200;
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

    /**
     * Remove a decoração e cobre o display físico inteiro.
     * Não usa {@code MAXIMIZED_BOTH}: no Linux/Raspberry o WM reduz a janela à área útil
     * (fora do painel), e a principal ficava menor que os diálogos.
     */
    public static void applyFullScreen(Window window) {
        try {
            if (!window.isDisplayable()) {
                if (window instanceof Frame) {
                    Frame frame = (Frame) window;
                    frame.setUndecorated(true);
                    frame.setResizable(false);
                } else if (window instanceof Dialog) {
                    Dialog dialog = (Dialog) window;
                    dialog.setUndecorated(true);
                    dialog.setResizable(false);
                }
            }
        } catch (IllegalComponentStateException ignored) {
            // janela já exibida: só reajusta os bounds
        }
        Rectangle screen = physicalScreenBounds();
        window.setBounds(screen);
        // Garante que um estado "maximizado" residual do WM não encolha a janela.
        if (window instanceof Frame) {
            ((Frame) window).setExtendedState(Frame.NORMAL);
        }
        window.setBounds(screen);
    }

    /** Reaplica a tela cheia depois que o WM mostrou a janela (alguns WMs redimensionam no open). */
    public static void keepFullScreen(Window window) {
        if (!isFullScreenEnabled()) {
            return;
        }
        if (window instanceof Frame) {
            Frame frame = (Frame) window;
            // Não forçar NORMAL enquanto minimizada — senão o botão Minimizar não surte efeito.
            if ((frame.getExtendedState() & Frame.ICONIFIED) != 0) {
                return;
            }
        }
        Rectangle screen = physicalScreenBounds();
        if (!screen.equals(window.getBounds())) {
            if (window instanceof Frame) {
                ((Frame) window).setExtendedState(Frame.NORMAL);
            }
            window.setBounds(screen);
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

    /** Mesma fonte do display de peso líquido e do código da tag na conferência. */
    public static Font fontMonitorDisplay() {
        return new Font(Font.MONOSPACED, Font.BOLD, 48);
    }

    /** Fonte monoespaçada usada nos códigos das tags RFID. */
    public static Font fontTagCode(Component c) {
        return fontMonitorDisplay();
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

    private static final String BUSY_OVERLAY_KEY = "workflow.ui.busyOverlay";
    private static final String BUSY_PREV_GLASS_KEY = "workflow.ui.busyPrevGlass";
    private static final String BUSY_PREV_CURSOR_KEY = "workflow.ui.busyPrevCursor";
    private static final String BUSY_MESSAGE_KEY = "workflow.ui.busyMessage";

    /**
     * Overlay modal de progresso (glass pane) — bloqueia cliques e informa o usuário
     * durante probe/conexão/encerramento sem parecer que a app travou.
     */
    public static void showBusy(Window window, String message) {
        if (window == null) {
            return;
        }
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> showBusy(window, message));
            return;
        }
        if (!(window instanceof RootPaneContainer)) {
            window.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
            return;
        }
        RootPaneContainer root = (RootPaneContainer) window;
        JRootPane rootPane = root.getRootPane();
        String text = (message == null || message.trim().isEmpty())
                ? "Processando, aguarde..."
                : message.trim();

        JComponent existing = (JComponent) rootPane.getClientProperty(BUSY_OVERLAY_KEY);
        if (existing != null) {
            JLabel label = (JLabel) existing.getClientProperty(BUSY_MESSAGE_KEY);
            if (label != null) {
                label.setText(text);
            }
            existing.setVisible(true);
            existing.revalidate();
            existing.repaint();
            return;
        }

        JPanel overlay = new JPanel(new GridBagLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setColor(new Color(37, 47, 61, 160));
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.dispose();
                super.paintComponent(g);
            }
        };
        overlay.setOpaque(false);
        overlay.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));

        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setOpaque(true);
        card.setBackground(BG_CARD);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_FOCUS, 1),
                empty(18, 22, 18, 22)));
        card.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel title = new JLabel("Aguarde");
        title.setFont(fontTitle(title));
        title.setForeground(TEXT_PRIMARY);
        title.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel detail = new JLabel(text);
        detail.setFont(fontStatus(detail));
        detail.setForeground(TEXT_SECONDARY);
        detail.setAlignmentX(Component.CENTER_ALIGNMENT);

        JProgressBar bar = new JProgressBar();
        bar.setIndeterminate(true);
        bar.setAlignmentX(Component.CENTER_ALIGNMENT);
        bar.setPreferredSize(new Dimension(220, 10));
        bar.setMaximumSize(new Dimension(280, 10));
        bar.setForeground(ACCENT);
        bar.setBackground(BG_CARD_HIGHLIGHT);
        bar.setBorderPainted(false);

        card.add(title);
        card.add(Box.createVerticalStrut(8));
        card.add(detail);
        card.add(Box.createVerticalStrut(14));
        card.add(bar);

        overlay.add(card);
        overlay.putClientProperty(BUSY_MESSAGE_KEY, detail);

        // Consome eventos para não clicar nos controles atrás
        overlay.addMouseListener(new java.awt.event.MouseAdapter() {
        });
        overlay.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
        });
        overlay.addKeyListener(new java.awt.event.KeyAdapter() {
        });
        overlay.setFocusable(true);

        rootPane.putClientProperty(BUSY_PREV_GLASS_KEY, root.getGlassPane());
        rootPane.putClientProperty(BUSY_PREV_CURSOR_KEY, window.getCursor());
        rootPane.putClientProperty(BUSY_OVERLAY_KEY, overlay);
        root.setGlassPane(overlay);
        overlay.setVisible(true);
        overlay.requestFocusInWindow();
        window.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
    }

    public static void hideBusy(Window window) {
        if (window == null) {
            return;
        }
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> hideBusy(window));
            return;
        }
        if (!(window instanceof RootPaneContainer)) {
            window.setCursor(Cursor.getDefaultCursor());
            return;
        }
        RootPaneContainer root = (RootPaneContainer) window;
        JRootPane rootPane = root.getRootPane();
        JComponent overlay = (JComponent) rootPane.getClientProperty(BUSY_OVERLAY_KEY);
        if (overlay == null) {
            window.setCursor(Cursor.getDefaultCursor());
            return;
        }
        overlay.setVisible(false);
        Object prevGlass = rootPane.getClientProperty(BUSY_PREV_GLASS_KEY);
        if (prevGlass instanceof Component) {
            root.setGlassPane((Component) prevGlass);
        } else {
            JPanel empty = new JPanel();
            empty.setOpaque(false);
            empty.setVisible(false);
            root.setGlassPane(empty);
        }
        Object prevCursor = rootPane.getClientProperty(BUSY_PREV_CURSOR_KEY);
        window.setCursor(prevCursor instanceof Cursor
                ? (Cursor) prevCursor
                : Cursor.getDefaultCursor());
        rootPane.putClientProperty(BUSY_OVERLAY_KEY, null);
        rootPane.putClientProperty(BUSY_PREV_GLASS_KEY, null);
        rootPane.putClientProperty(BUSY_PREV_CURSOR_KEY, null);
        rootPane.putClientProperty(BUSY_MESSAGE_KEY, null);
    }

    public static boolean isBusyShowing(Window window) {
        if (!(window instanceof RootPaneContainer)) {
            return false;
        }
        JRootPane rootPane = ((RootPaneContainer) window).getRootPane();
        return rootPane.getClientProperty(BUSY_OVERLAY_KEY) != null;
    }

    /**
     * Pop-up grande após validação (sucesso ou divergência). Sem botão —
     * fecha sozinho após {@code durationMs}. Cada chamada tem timer próprio
     * (mensagens independentes; não sincronizam o fechamento).
     */
    public static void showValidationOutcome(Window window, boolean success,
                                             String title, String detail) {
        showTimedOutcome(window, success ? OutcomeStyle.SUCCESS : OutcomeStyle.ERROR,
                title, detail, success ? 5000 : 10_000, null);
    }

    /** Pop-up de validação com foto opcional (ex.: sucesso com captura). */
    public static void showValidationOutcome(Window window, boolean success,
                                             String title, String detail, String photoPath) {
        int durationMs = success ? 5000 : 10_000;
        showTimedOutcome(window, success ? OutcomeStyle.SUCCESS : OutcomeStyle.ERROR,
                title, detail, durationMs, photoPath);
    }

    /**
     * Mensagem temporária sem botão (ex.: carregando próximo pedido).
     */
    public static void showTimedOutcome(Window window, boolean success,
                                        String title, String detail, int durationMs) {
        showTimedOutcome(window, success ? OutcomeStyle.SUCCESS : OutcomeStyle.ERROR,
                title, detail, durationMs, null);
    }

    public static void showInfoOutcome(Window window, String title, String detail, int durationMs) {
        showTimedOutcome(window, OutcomeStyle.INFO, title, detail, durationMs, null);
    }

    private enum OutcomeStyle {
        SUCCESS, ERROR, INFO
    }

    private static void showTimedOutcome(Window window, OutcomeStyle style,
                                         String title, String detail, int durationMs,
                                         String photoPath) {
        if (window == null) {
            return;
        }
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(
                    () -> showTimedOutcome(window, style, title, detail, durationMs, photoPath));
            return;
        }

        String safeTitle = (title == null || title.trim().isEmpty())
                ? (style == OutcomeStyle.SUCCESS ? "Concluído com sucesso"
                : style == OutcomeStyle.ERROR ? "Divergência detectada"
                : "Aguarde")
                : title.trim();
        String safeDetail = detail == null ? "" : detail.trim();
        int safeDuration = Math.max(500, durationMs);

        final JDialog dialog = new JDialog(window instanceof Frame ? (Frame) window
                : window instanceof Dialog ? (Dialog) window : null,
                safeTitle, Dialog.ModalityType.MODELESS);
        dialog.setUndecorated(true);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setAlwaysOnTop(true);

        JPanel overlay = new JPanel(new GridBagLayout());
        overlay.setOpaque(true);
        overlay.setBackground(new Color(37, 47, 61, 200));

        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setOpaque(true);
        card.setBackground(BG_CARD);
        Color accent = style == OutcomeStyle.SUCCESS ? new Color(0x2E, 0x7D, 0x32)
                : style == OutcomeStyle.ERROR ? new Color(0xC6, 0x28, 0x28)
                : new Color(0x15, 0x65, 0xC0);
        String badgeText = style == OutcomeStyle.SUCCESS ? "✓  SUCESSO"
                : style == OutcomeStyle.ERROR ? "!  DIVERGÊNCIA"
                : "…  AGUARDE";
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(accent, 3),
                empty(20, 24, 18, 24)));
        card.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel badge = new JLabel(badgeText, SwingConstants.CENTER);
        badge.setOpaque(true);
        badge.setBackground(accent);
        badge.setForeground(Color.WHITE);
        badge.setFont(badge.getFont().deriveFont(Font.BOLD, 18f));
        badge.setAlignmentX(Component.CENTER_ALIGNMENT);
        badge.setBorder(empty(8, 16, 8, 16));
        badge.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));

        JLabel titleLabel = new JLabel("<html><div style='text-align:center; width:320px;'>"
                + escapeHtml(safeTitle) + "</div></html>", SwingConstants.CENTER);
        titleLabel.setFont(fontTitle(titleLabel).deriveFont(Font.BOLD, 16f));
        titleLabel.setForeground(TEXT_PRIMARY);
        titleLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        String defaultDetail = style == OutcomeStyle.SUCCESS
                ? "Peso e tags conferem com o pedido."
                : style == OutcomeStyle.ERROR
                ? "Reiniciando a leitura das tags."
                : "Aguarde o próximo passo.";
        JLabel detailLabel = new JLabel("<html><div style='text-align:center; width:340px;'>"
                + escapeHtml(safeDetail.isEmpty() ? defaultDetail : safeDetail)
                + "</div></html>", SwingConstants.CENTER);
        detailLabel.setFont(fontStatus(detailLabel).deriveFont(14f));
        detailLabel.setForeground(TEXT_SECONDARY);
        detailLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        card.add(badge);
        card.add(Box.createVerticalStrut(14));
        card.add(titleLabel);
        card.add(Box.createVerticalStrut(10));
        card.add(detailLabel);

        JComponent photoComponent = buildOutcomePhotoPreview(photoPath);
        if (photoComponent != null) {
            card.add(Box.createVerticalStrut(12));
            card.add(photoComponent);
        }

        overlay.add(card);
        dialog.setContentPane(overlay);
        dialog.pack();
        Dimension size = dialog.getSize();
        int w = Math.max(size.width, photoComponent != null ? 480 : 420);
        int h = Math.max(size.height, photoComponent != null ? 360 : 200);
        dialog.setSize(w, h);
        dialog.setLocationRelativeTo(window);
        dialog.setVisible(true);

        javax.swing.Timer autoClose = new javax.swing.Timer(safeDuration, e -> {
            if (dialog.isDisplayable()) {
                dialog.dispose();
            }
        });
        autoClose.setRepeats(false);
        autoClose.start();
    }

    /** Miniatura da foto no pop-up de sucesso; null se caminho inválido. */
    private static JComponent buildOutcomePhotoPreview(String photoPath) {
        if (photoPath == null || photoPath.trim().isEmpty()) {
            return null;
        }
        File file = new File(photoPath.trim());
        if (!file.isFile() || file.length() == 0) {
            return null;
        }
        ImageIcon icon = new ImageIcon(file.getAbsolutePath());
        if (icon.getIconWidth() <= 0 || icon.getIconHeight() <= 0) {
            return null;
        }
        int maxW = 320;
        int maxH = 220;
        int iw = icon.getIconWidth();
        int ih = icon.getIconHeight();
        double scale = Math.min((double) maxW / iw, (double) maxH / ih);
        scale = Math.min(1.0, scale);
        int tw = Math.max(1, (int) Math.round(iw * scale));
        int th = Math.max(1, (int) Math.round(ih * scale));
        Image scaled = icon.getImage().getScaledInstance(tw, th, Image.SCALE_SMOOTH);
        JLabel imageLabel = new JLabel(new ImageIcon(scaled));
        imageLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        imageLabel.setBorder(BorderFactory.createLineBorder(BORDER, 1));
        imageLabel.setOpaque(true);
        imageLabel.setBackground(BG_CARD);
        return imageLabel;
    }

    private static String escapeHtml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("\n", "<br>");
    }
}
