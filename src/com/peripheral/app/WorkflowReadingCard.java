package com.peripheral.app;

import com.peripheral.scale.ScaleWeightFormat;
import com.peripheral.workflow.WorkflowReadingRecord;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * Uma leitura do histórico em linha única, dimensionada para a tela de 7":
 * índice, peso, hora e quantidade de produtos, com atalhos de foto e etiqueta.
 * Tocar na linha abre o detalhe com os seriais.
 */
public class WorkflowReadingCard extends JPanel {

    public interface ActionListener {
        void onViewPhoto(WorkflowReadingRecord record);

        void onViewLabel(WorkflowReadingRecord record);

        void onViewDetails(WorkflowReadingRecord record);
    }

    private static final int ROW_HEIGHT = 44;

    private final WorkflowReadingRecord record;
    private final boolean photoEnabled;
    private final boolean labelEnabled;
    private boolean highlight;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss");

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
        setLayout(new BorderLayout(8, 0));
        setBorder(WorkflowUiTheme.empty(0, 10, 0, 6));
        setPreferredSize(new Dimension(0, ROW_HEIGHT));
        setMinimumSize(new Dimension(0, ROW_HEIGHT));
        setMaximumSize(new Dimension(Integer.MAX_VALUE, ROW_HEIGHT));
        buildContent(listener);

        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                listener.onViewDetails(record);
            }
        });
    }

    public WorkflowReadingRecord getRecord() {
        return record;
    }

    public void setHighlight(boolean highlight) {
        this.highlight = highlight;
        repaint();
    }

    private void buildContent(ActionListener listener) {
        JLabel lbIndex = new JLabel("#" + record.getIndex());
        lbIndex.setFont(lbIndex.getFont().deriveFont(Font.BOLD, 12f));
        lbIndex.setForeground(WorkflowUiTheme.TEXT_MUTED);
        add(lbIndex, BorderLayout.WEST);

        JPanel info = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        info.setOpaque(false);

        JLabel lbWeight = new JLabel(ScaleWeightFormat.formatGrams(record.getWeightKg()));
        lbWeight.setFont(WorkflowUiTheme.fontWeight(lbWeight));
        lbWeight.setForeground(WorkflowUiTheme.TEXT_PRIMARY);

        JLabel lbUnit = new JLabel(ScaleWeightFormat.UNIT);
        lbUnit.setFont(WorkflowUiTheme.fontWeightUnit(lbUnit));
        lbUnit.setForeground(WorkflowUiTheme.TEXT_SECONDARY);

        info.add(lbWeight);
        info.add(lbUnit);
        info.add(separator());
        info.add(meta(timeFormat.format(new Date(record.getTimestampMs()))));
        info.add(separator());
        info.add(meta(describeProducts(record.getTagCodes())));
        add(info, BorderLayout.CENTER);

        boolean photoAvailable = photoEnabled && record.hasPhoto()
                && new File(record.getPhotoPath()).isFile();
        boolean labelAvailable = labelEnabled && record.hasLabel()
                && new File(record.getLabelPdfPath()).isFile();

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        actions.setOpaque(false);
        if (photoEnabled) {
            actions.add(new IconButton(IconButton.Kind.PHOTO, photoAvailable, "Ver foto",
                    () -> listener.onViewPhoto(record)));
        }
        if (labelEnabled) {
            actions.add(new IconButton(IconButton.Kind.LABEL, labelAvailable, "Ver etiqueta",
                    () -> listener.onViewLabel(record)));
        }
        if (actions.getComponentCount() > 0) {
            add(actions, BorderLayout.EAST);
        }
    }

    private JLabel meta(String text) {
        JLabel label = new JLabel(text);
        label.setFont(WorkflowUiTheme.fontMeta(label));
        label.setForeground(WorkflowUiTheme.TEXT_SECONDARY);
        return label;
    }

    private JLabel separator() {
        JLabel label = new JLabel("·");
        label.setFont(WorkflowUiTheme.fontMeta(label));
        label.setForeground(WorkflowUiTheme.TEXT_MUTED);
        return label;
    }

    private static String describeProducts(List<String> tagCodes) {
        int count = tagCodes != null ? tagCodes.size() : 0;
        if (count == 0) {
            return "sem produtos";
        }
        return count == 1 ? "1 produto" : count + " produtos";
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int w = getWidth();
        int h = getHeight();

        g2.setColor(highlight ? WorkflowUiTheme.BG_CARD_HIGHLIGHT : WorkflowUiTheme.BG_CARD);
        g2.fill(new RoundRectangle2D.Float(0, 0, w, h,
                WorkflowUiTheme.RADIUS_CHIP, WorkflowUiTheme.RADIUS_CHIP));

        g2.setColor(highlight ? WorkflowUiTheme.BORDER_FOCUS : WorkflowUiTheme.BORDER);
        g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1,
                WorkflowUiTheme.RADIUS_CHIP, WorkflowUiTheme.RADIUS_CHIP));

        if (highlight) {
            g2.setColor(WorkflowUiTheme.ACCENT);
            g2.fillRoundRect(0, 6, 4, h - 12, 4, 4);
        }

        g2.dispose();
        super.paintComponent(g);
    }

    /** Ícone desenhado em vetor: independe de fonte com suporte a emoji no Raspberry Pi. */
    private static final class IconButton extends JComponent {

        enum Kind {
            PHOTO, LABEL
        }

        private static final int SIZE = 36;

        private final Kind kind;
        private final boolean enabled;
        private boolean hovered;

        IconButton(Kind kind, boolean enabled, String tooltip, Runnable action) {
            this.kind = kind;
            this.enabled = enabled;
            setOpaque(false);
            setToolTipText(tooltip);
            setPreferredSize(new Dimension(SIZE, SIZE));
            setMinimumSize(new Dimension(SIZE, SIZE));
            setMaximumSize(new Dimension(SIZE, SIZE));
            setCursor(enabled
                    ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    : Cursor.getDefaultCursor());

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

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();
            Color stroke;

            if (!enabled) {
                g2.setColor(WorkflowUiTheme.PILL_DISABLED);
                g2.fillRoundRect(0, 0, w, h, WorkflowUiTheme.RADIUS_CHIP, WorkflowUiTheme.RADIUS_CHIP);
                stroke = WorkflowUiTheme.TEXT_MUTED;
            } else if (hovered) {
                g2.setColor(WorkflowUiTheme.ACCENT);
                g2.fillRoundRect(0, 0, w, h, WorkflowUiTheme.RADIUS_CHIP, WorkflowUiTheme.RADIUS_CHIP);
                stroke = WorkflowUiTheme.TEXT_ON_ACCENT;
            } else {
                g2.setColor(WorkflowUiTheme.PILL_BG);
                g2.fillRoundRect(0, 0, w, h, WorkflowUiTheme.RADIUS_CHIP, WorkflowUiTheme.RADIUS_CHIP);
                g2.setColor(WorkflowUiTheme.PILL_BORDER);
                g2.drawRoundRect(0, 0, w - 1, h - 1,
                        WorkflowUiTheme.RADIUS_CHIP, WorkflowUiTheme.RADIUS_CHIP);
                stroke = WorkflowUiTheme.TEXT_PRIMARY;
            }

            g2.setColor(stroke);
            g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            if (kind == Kind.PHOTO) {
                paintPhotoIcon(g2, w, h);
            } else {
                paintLabelIcon(g2, w, h);
            }
            g2.dispose();
        }

        private void paintPhotoIcon(Graphics2D g2, int w, int h) {
            int s = 18;
            int x = (w - s) / 2;
            int y = (h - s) / 2 + 1;
            g2.draw(new RoundRectangle2D.Float(x, y + 4, s, s - 7, 3, 3));
            g2.draw(new RoundRectangle2D.Float(x + 5f, y + 1f, 7f, 4f, 2f, 2f));
            g2.draw(new Ellipse2D.Float(x + s / 2f - 3.5f, y + 7.5f, 7f, 7f));
        }

        private void paintLabelIcon(Graphics2D g2, int w, int h) {
            int s = 18;
            int x = (w - s) / 2;
            int y = (h - s) / 2;
            Path2D.Float tag = new Path2D.Float();
            tag.moveTo(x + 1f, y + s / 2f);
            tag.lineTo(x + 6f, y + 3f);
            tag.lineTo(x + s - 1f, y + 3f);
            tag.lineTo(x + s - 1f, y + s - 3f);
            tag.lineTo(x + 6f, y + s - 3f);
            tag.closePath();
            g2.draw(tag);
            g2.draw(new Ellipse2D.Float(x + 7f, y + s / 2f - 1.5f, 3f, 3f));
        }
    }
}
