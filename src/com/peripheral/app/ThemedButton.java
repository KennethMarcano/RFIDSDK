package com.peripheral.app;

import javax.swing.*;
import javax.swing.plaf.basic.BasicButtonUI;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;

public class ThemedButton extends JButton {

    public enum Variant {
        PRIMARY, SECONDARY, SUCCESS, DANGER
    }

    /** Tamanhos pensados para toque com o dedo em tela de 7". */
    public enum Size {
        REGULAR, SMALL, LARGE
    }

    private Variant variant;
    private Size size = Size.REGULAR;
    private boolean hovered;

    public ThemedButton(String text) {
        this(text, Variant.SECONDARY);
    }

    public ThemedButton(String text, Variant variant) {
        super(text);
        this.variant = variant;
        init();
    }

    public void setVariant(Variant variant) {
        this.variant = variant;
        repaint();
    }

    public ThemedButton withSize(Size size) {
        this.size = size;
        applySizeFont();
        revalidate();
        repaint();
        return this;
    }

    private void applySizeFont() {
        switch (size) {
            case LARGE:
                setFont(getFont().deriveFont(Font.BOLD, 15f));
                setBorder(WorkflowUiTheme.empty(12, 24, 12, 24));
                break;
            case SMALL:
                setFont(getFont().deriveFont(Font.BOLD, 12f));
                setBorder(WorkflowUiTheme.empty(7, 12, 7, 12));
                break;
            case REGULAR:
            default:
                setFont(getFont().deriveFont(Font.BOLD, 13f));
                setBorder(WorkflowUiTheme.empty(10, 18, 10, 18));
                break;
        }
    }

    private void init() {
        // GTK/Metal no Linux ignoram setBackground; BasicButtonUI garante cliques e pintura corretos.
        setUI(new BasicButtonUI());
        setFocusPainted(false);
        setBorderPainted(false);
        setContentAreaFilled(false);
        setOpaque(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        applySizeFont();

        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                if (isEnabled()) {
                    hovered = true;
                    repaint();
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hovered = false;
                repaint();
            }
        });
        addPropertyChangeListener("enabled", evt -> repaint());
    }

    private int minHeight() {
        switch (size) {
            case LARGE:
                return 52;
            case SMALL:
                return WorkflowUiTheme.TOUCH_HEIGHT_SM;
            case REGULAR:
            default:
                return WorkflowUiTheme.TOUCH_HEIGHT;
        }
    }

    private int minWidth() {
        switch (size) {
            case LARGE:
                return 168;
            case SMALL:
                return 88;
            case REGULAR:
            default:
                return WorkflowUiTheme.TOUCH_MIN_WIDTH;
        }
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension base = super.getPreferredSize();
        return new Dimension(Math.max(base.width, minWidth()), Math.max(base.height, minHeight()));
    }

    @Override
    public Dimension getMinimumSize() {
        return new Dimension(minWidth(), minHeight());
    }

    @Override
    public Dimension getMaximumSize() {
        return getPreferredSize();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int w = getWidth();
        int h = getHeight();
        int radius = 8;

        Color background;
        Color foreground;
        Color border;

        if (!isEnabled()) {
            background = WorkflowUiTheme.PILL_DISABLED;
            foreground = WorkflowUiTheme.TEXT_MUTED;
            border = WorkflowUiTheme.BORDER;
        } else {
            switch (variant) {
                case PRIMARY:
                    background = hovered ? WorkflowUiTheme.ACCENT_HOVER : WorkflowUiTheme.ACCENT;
                    foreground = WorkflowUiTheme.TEXT_ON_ACCENT;
                    border = background;
                    break;
                case SUCCESS:
                    background = hovered ? WorkflowUiTheme.SUCCESS.brighter() : WorkflowUiTheme.SUCCESS;
                    foreground = Color.WHITE;
                    border = background;
                    break;
                case DANGER:
                    background = hovered ? WorkflowUiTheme.DANGER.brighter() : WorkflowUiTheme.DANGER;
                    foreground = Color.WHITE;
                    border = background;
                    break;
                case SECONDARY:
                default:
                    background = hovered ? WorkflowUiTheme.BG_CARD_HIGHLIGHT : WorkflowUiTheme.BG_CARD;
                    foreground = WorkflowUiTheme.TEXT_PRIMARY;
                    border = hovered ? WorkflowUiTheme.ACCENT : WorkflowUiTheme.BORDER;
                    break;
            }
        }

        g2.setColor(background);
        g2.fill(new RoundRectangle2D.Float(0, 0, w, h, radius, radius));
        g2.setColor(border);
        g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, radius, radius));

        g2.setColor(foreground);
        g2.setFont(getFont());
        FontMetrics fm = g2.getFontMetrics();
        String text = getText();
        int x = (w - fm.stringWidth(text)) / 2;
        int y = (h + fm.getAscent() - fm.getDescent()) / 2;
        g2.drawString(text, x, y);
        g2.dispose();
    }
}
