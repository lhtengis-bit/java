package cafe.server.ui;

import cafe.server.config.ServerConfig;
import cafe.server.db.StationDao;
import cafe.server.db.TransactionDao;
import cafe.server.i18n.Messages;
import cafe.server.mail.ShiftReportMailer;
import cafe.server.net.ClientHandler;
import cafe.server.net.ClientRegistry;
import cafe.shared.Protocol;
import cafe.shared.Station;
import cafe.shared.StationStatus;

import javax.swing.*;
import java.awt.*;
import java.math.BigDecimal;
import java.util.Locale;

/**
 * Top-level Cashier window. Composes the menu bar, JTable, and control
 * panel using BorderLayout, and wires DB / network actions.
 *
 * <p>Every long-running call (DB query, SMTP send) is dispatched via a
 * {@link SwingWorker}, so the EDT stays responsive.
 */
public final class CashierFrame extends JFrame {

    private final Messages msg;
    private final StationDao stationDao;
    private final TransactionDao txDao;
    private final ClientRegistry registry;
    private final ServerConfig cfg;

    private final StationTableModel tableModel;
    private final JTable table;
    private final JComboBox<Integer> stationBox = new JComboBox<>();
    private final JTextField amountField = new JTextField(8);
    private final JButton addFundsBtn = new JButton();
    private final JButton lockBtn = new JButton();
    private final JLabel statusBar = new JLabel(" ");

    private StationRefreshWorker refresher;

    public CashierFrame(ServerConfig cfg,
                        Messages msg,
                        StationDao stationDao,
                        TransactionDao txDao,
                        ClientRegistry registry) {
        super();
        this.cfg = cfg;
        this.msg = msg;
        this.stationDao = stationDao;
        this.txDao = txDao;
        this.registry = registry;

        this.tableModel = new StationTableModel(msg);
        this.table = new JTable(tableModel);
        this.table.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        this.table.getSelectionModel().setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        buildUi();
        wireLocaleListener();
    }

    private void buildUi() {
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(900, 520);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout(8, 8));

        setJMenuBar(buildMenuBar());
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(buildControlPanel(),     BorderLayout.SOUTH);

        statusBar.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        add(statusBar, BorderLayout.PAGE_END);
    }

    private JMenuBar buildMenuBar() {
        JMenuBar bar = new JMenuBar();

        JMenu mSystem = new JMenu();
        JMenuItem miConfig = new JMenuItem();
        JMenuItem miExit   = new JMenuItem();
        miConfig.addActionListener(e -> JOptionPane.showMessageDialog(this,
            "Config file: see config.properties next to the JAR."));
        miExit.addActionListener(e -> dispose());
        mSystem.add(miConfig);
        mSystem.addSeparator();
        mSystem.add(miExit);

        JMenu mLang = new JMenu();
        JMenuItem miEn = new JMenuItem();
        JMenuItem miMn = new JMenuItem();
        miEn.addActionListener(e -> msg.setLocale(Locale.ENGLISH));
        miMn.addActionListener(e -> msg.setLocale(new Locale("mn")));
        mLang.add(miEn);
        mLang.add(miMn);

        JMenu mReport = new JMenu();
        JMenuItem miClose = new JMenuItem();
        miClose.addActionListener(e -> closeShiftAndEmail());
        mReport.add(miClose);

        bar.add(mSystem);
        bar.add(mLang);
        bar.add(mReport);

        // Re-label when the language changes.
        msg.onLocaleChange(l -> {
            mSystem.setText(msg.get("menu.system"));
            miConfig.setText(msg.get("menu.system.config"));
            miExit.setText(msg.get("menu.system.exit"));
            mLang.setText(msg.get("menu.language"));
            miEn.setText(msg.get("menu.language.en"));
            miMn.setText(msg.get("menu.language.mn"));
            mReport.setText(msg.get("menu.report"));
            miClose.setText(msg.get("menu.report.close"));
        });
        return bar;
    }

    private JPanel buildControlPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));

        JLabel lblStation = new JLabel();
        JLabel lblAmount  = new JLabel();

        addFundsBtn.addActionListener(e -> onAddFunds());
        lockBtn.addActionListener(e -> onLockSelected());

        panel.add(lblStation);
        panel.add(stationBox);
        panel.add(lblAmount);
        panel.add(amountField);
        panel.add(addFundsBtn);
        panel.add(lockBtn);

        msg.onLocaleChange(l -> {
            lblStation.setText(msg.get("ctrl.station"));
            lblAmount.setText(msg.get("ctrl.amount"));
            addFundsBtn.setText(msg.get("ctrl.addfunds"));
            lockBtn.setText(msg.get("ctrl.lock"));
            setTitle(msg.get("app.title"));
        });
        return panel;
    }

    private void wireLocaleListener() {
        msg.onLocaleChange(l ->
            statusBar.setText(msg.format("status.connected", registry.size())));
    }

    /** Kick off the polling worker. Call AFTER the frame is visible. */
    public void startRefresh() {
        int interval = cfg.intg("ui.refresh.interval.seconds", 2);
        refresher = new StationRefreshWorker(stationDao, tableModel, interval) {
            @Override
            protected void process(java.util.List<java.util.List<Station>> chunks) {
                super.process(chunks);
                refreshStationBox();
                statusBar.setText(msg.format("status.connected", registry.size()));
            }
        };
        refresher.execute();
    }

    private void refreshStationBox() {
        Integer selected = (Integer) stationBox.getSelectedItem();
        stationBox.removeAllItems();
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            stationBox.addItem(tableModel.rowAt(i).getStationId());
        }
        if (selected != null) stationBox.setSelectedItem(selected);
    }

    private void onAddFunds() {
        Integer stationId = (Integer) stationBox.getSelectedItem();
        if (stationId == null) {
            JOptionPane.showMessageDialog(this, msg.get("dialog.fund.noStation"));
            return;
        }

        final BigDecimal amount;
        try {
            amount = new BigDecimal(amountField.getText().trim());
            if (amount.signum() <= 0) throw new NumberFormatException();
        } catch (NumberFormatException nfe) {
            JOptionPane.showMessageDialog(this, msg.get("dialog.fund.invalid"));
            return;
        }

        // Pricing rule (kept simple for a 2nd-year project): 1 unit = 60 seconds.
        // Adjust to your local price. e.g. 1000 MNT -> 3600 seconds.
        final int secondsToAdd = amount.multiply(BigDecimal.valueOf(60)).intValue();

        new SwingWorker<Boolean, Void>() {
            String errorMsg = null;
            @Override protected Boolean doInBackground() {
                try {
                    var maybe = registry.get(stationId);
                    if (maybe.isEmpty()) {
                        errorMsg = msg.format("dialog.fund.offline", stationId);
                        return false;
                    }
                    // 1. write to ledger (immutable). 2. update station row.
                    txDao.insert(stationId, amount);
                    stationDao.addTimeAndUnlock(stationId, secondsToAdd);
                    // 3. push UNLOCK to the kiosk.
                    maybe.get().send(Protocol.unlock(secondsToAdd));
                    return true;
                } catch (Exception ex) {
                    errorMsg = ex.getMessage();
                    return false;
                }
            }
            @Override protected void done() {
                try {
                    if (get()) {
                        JOptionPane.showMessageDialog(CashierFrame.this,
                            msg.format("dialog.fund.success", secondsToAdd, stationId));
                        amountField.setText("");
                    } else {
                        JOptionPane.showMessageDialog(CashierFrame.this,
                            errorMsg != null ? errorMsg : msg.get("dialog.fund.invalid"),
                            "Error", JOptionPane.ERROR_MESSAGE);
                    }
                } catch (Exception ignored) { }
            }
        }.execute();
    }

    private void onLockSelected() {
        Integer stationId = (Integer) stationBox.getSelectedItem();
        if (stationId == null) return;
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() {
                try {
                    stationDao.zeroTimeAndLock(stationId);
                    registry.get(stationId).ifPresent(h -> h.send(Protocol.CMD_LOCK));
                } catch (Exception ignored) { }
                return null;
            }
        }.execute();
    }

    private void closeShiftAndEmail() {
        new SwingWorker<Void, Void>() {
            Exception failure;
            @Override protected Void doInBackground() {
                try {
                    new ShiftReportMailer(cfg, txDao).sendNow();
                } catch (Exception e) {
                    failure = e;
                }
                return null;
            }
            @Override protected void done() {
                if (failure == null) {
                    JOptionPane.showMessageDialog(CashierFrame.this,
                        msg.get("dialog.report.ok"));
                } else {
                    JOptionPane.showMessageDialog(CashierFrame.this,
                        msg.format("dialog.report.fail", failure.getMessage()),
                        "Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }
}
