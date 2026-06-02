package com.peripheral.app;

import com.peripheral.session.PeripheralSessionManager;
import com.peripheral.session.PeripheralSlot;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.function.Consumer;

public class PeripheralConfigDialog extends JDialog {

    public interface ConfigDialogListener {
        void onConfigurationClosed(PeripheralSlot slot, boolean connected);
    }

    private final PeripheralConnectionPanel connectionPanel;
    private final ConfigDialogListener listener;
    private final PeripheralSlot slot;
    private boolean notified;

    public PeripheralConfigDialog(Window owner, PeripheralSlot slot,
                                  PeripheralSessionManager sessionManager,
                                  ConfigDialogListener listener,
                                  Consumer<String> logConsumer) {
        super(owner, "Configurar " + slot.getLabel(), ModalityType.APPLICATION_MODAL);
        this.slot = slot;
        this.listener = listener;

        PeripheralConnectionPanel.ConnectionListener connListener =
                new PeripheralConnectionPanel.ConnectionListener() {
                    @Override
                    public void onConnectionChanged(PeripheralSlot s, boolean connected) {
                        if (listener != null) {
                            listener.onConfigurationClosed(s, connected);
                        }
                    }

                    @Override
                    public void onLog(String message) {
                        if (logConsumer != null) {
                            logConsumer.accept(message);
                        }
                    }
                };

        connectionPanel = new PeripheralConnectionPanel(
                slot, sessionManager, connListener, port -> {});
        connectionPanel.setOwnerWindow(this);
        connectionPanel.setBorder(null);

        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 8, 12));

        JLabel hint = new JLabel("<html>Selecione fabricante, modelo e porta. "
                + "Use <b>Testar porta</b> e <b>Conectar</b>. Ao terminar, clique em <b>Concluído</b>.</html>");
        content.add(hint, BorderLayout.NORTH);

        JScrollPane scroll = new JScrollPane(connectionPanel);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        content.add(scroll, BorderLayout.CENTER);

        JButton btnDone = new JButton("Concluído");
        btnDone.addActionListener(e -> closeDialog());
        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        south.add(btnDone);
        content.add(south, BorderLayout.SOUTH);

        setContentPane(content);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                notifyClosed();
            }
        });
        pack();
        setMinimumSize(new Dimension(Math.max(520, getWidth()), Math.max(420, getHeight())));
        setLocationRelativeTo(owner);
    }

    public void showDialog() {
        connectionPanel.refreshPortsFromOutside();
        setVisible(true);
    }

    private void closeDialog() {
        dispose();
        notifyClosed();
    }

    private void notifyClosed() {
        if (notified) {
            return;
        }
        notified = true;
        if (listener != null) {
            listener.onConfigurationClosed(slot, connectionPanel.isConnected());
        }
    }
}
