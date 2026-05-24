package cafe.server.ui;

import cafe.server.config.ServerConfig;
import cafe.server.db.Database;
import cafe.server.db.StationDao;
import cafe.server.db.TransactionDao;
import cafe.server.i18n.Messages;
import cafe.server.mail.ShiftReportMailer;
import cafe.server.net.ClientRegistry;
import cafe.shared.CashTransaction;
import cafe.shared.Protocol;
import cafe.shared.Station;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

public final class CashierFrame extends JFrame {

    private final Messages msg;
    private final Database db;
    private final StationDao stationDao;
    private final TransactionDao txDao;
    private final ClientRegistry registry;
    private final ServerConfig cfg;

    private final StationTableModel tableModel;
    private final JTable table;
    private final JComboBox<Integer> stationBox = new JComboBox<>();
    private final JTextField amountField = new JTextField(10);
    private final JButton addFundsBtn = new JButton();
    private final JButton lockBtn     = new JButton();
    private final JButton setTimeBtn  = new JButton();
    private final JLabel  statusBar   = new JLabel(" ");
    private final JLabel  rateHint    = new JLabel();

    private JTabbedPane tabbedPane;
    private DefaultTableModel txTableModel;

    private StationRefreshWorker refresher;

    public CashierFrame(ServerConfig cfg,
                        Messages msg,
                        Database db,
                        StationDao stationDao,
                        TransactionDao txDao,
                        ClientRegistry registry) {
        super();
        this.cfg = cfg;
        this.msg = msg;
        this.db = db;
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

    // ---- UI construction ---------------------------------------------------

    private void buildUi() {
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(960, 580);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());

        setJMenuBar(buildMenuBar());
        add(buildHeader(), BorderLayout.NORTH);

        styleTable();

        // U6: clicking a table row auto-selects that station in the dropdown
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int row = table.getSelectedRow();
                if (row >= 0) {
                    Station s = tableModel.rowAt(row);
                    if (s != null) stationBox.setSelectedItem(s.getStationId());
                }
            }
        });

        // F1: tabbed pane — Stations tab + Transactions tab
        tabbedPane = new JTabbedPane();
        JScrollPane stationScroll = new JScrollPane(table);
        stationScroll.setBorder(BorderFactory.createEmptyBorder());
        tabbedPane.addTab("", stationScroll);
        tabbedPane.addTab("", buildTransactionPanel());

        tabbedPane.addChangeListener(e -> {
            if (tabbedPane.getSelectedIndex() == 1) refreshTransactions();
        });

        msg.onLocaleChange(l -> {
            tabbedPane.setTitleAt(0, msg.get("tab.stations"));
            tabbedPane.setTitleAt(1, msg.get("tab.transactions"));
        });

        add(tabbedPane, BorderLayout.CENTER);

        JPanel south = new JPanel(new BorderLayout());
        south.add(buildControlPanel(), BorderLayout.CENTER);
        statusBar.setBorder(BorderFactory.createEmptyBorder(4, 12, 4, 12));
        statusBar.setFont(statusBar.getFont().deriveFont(Font.ITALIC, 11f));
        statusBar.setForeground(new Color(100, 100, 100));
        south.add(statusBar, BorderLayout.SOUTH);
        add(south, BorderLayout.SOUTH);
    }

    private JPanel buildHeader() {
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(new Color(24, 58, 105));
        header.setBorder(BorderFactory.createEmptyBorder(14, 20, 14, 20));

        JLabel titleLbl = new JLabel();
        titleLbl.setForeground(Color.WHITE);
        titleLbl.setFont(titleLbl.getFont().deriveFont(Font.BOLD, 20f));

        JLabel subLbl = new JLabel("Cashier Terminal");
        subLbl.setForeground(new Color(160, 195, 240));
        subLbl.setFont(subLbl.getFont().deriveFont(13f));

        JPanel textStack = new JPanel();
        textStack.setOpaque(false);
        textStack.setLayout(new BoxLayout(textStack, BoxLayout.Y_AXIS));
        textStack.add(titleLbl);
        textStack.add(Box.createVerticalStrut(2));
        textStack.add(subLbl);

        header.add(textStack, BorderLayout.WEST);
        msg.onLocaleChange(l -> titleLbl.setText(msg.get("app.title")));
        return header;
    }

    private void styleTable() {
        table.setRowHeight(30);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setFont(table.getFont().deriveFont(13f));
        table.getTableHeader().setFont(
            table.getTableHeader().getFont().deriveFont(Font.BOLD, 12f));
        table.getTableHeader().setReorderingAllowed(false);
        table.setSelectionBackground(new Color(210, 228, 255));
        table.setSelectionForeground(Color.BLACK);

        table.getColumnModel().getColumn(0).setPreferredWidth(80);
        table.getColumnModel().getColumn(1).setPreferredWidth(180);
        table.getColumnModel().getColumn(2).setPreferredWidth(110);
        table.getColumnModel().getColumn(3).setPreferredWidth(140);

        table.getColumnModel().getColumn(2).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object val,
                    boolean sel, boolean focus, int row, int col) {
                super.getTableCellRendererComponent(t, val, sel, focus, row, col);
                if (!sel && val != null) {
                    switch (val.toString()) {
                        case "UNLOCKED" -> { setForeground(new Color(34, 139, 34)); setFont(getFont().deriveFont(Font.BOLD)); }
                        case "LOCKED"   -> { setForeground(new Color(195, 95, 0));  setFont(getFont().deriveFont(Font.PLAIN)); }
                        default         -> { setForeground(new Color(130, 130, 130)); setFont(getFont().deriveFont(Font.PLAIN)); }
                    }
                }
                return this;
            }
        });
    }

    private JScrollPane buildTransactionPanel() {
        String[] cols = {"TXN #", "Station", "Amount (₮)", "Time"};
        txTableModel = new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        JTable txTable = new JTable(txTableModel);
        txTable.setRowHeight(26);
        txTable.setShowGrid(false);
        txTable.setIntercellSpacing(new Dimension(0, 0));
        txTable.setFont(txTable.getFont().deriveFont(13f));
        txTable.getTableHeader().setFont(txTable.getTableHeader().getFont().deriveFont(Font.BOLD, 12f));
        txTable.getTableHeader().setReorderingAllowed(false);
        JScrollPane scroll = new JScrollPane(txTable);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        return scroll;
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
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.X_AXIS));
        panel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(210, 210, 210)),
            BorderFactory.createEmptyBorder(12, 16, 12, 16)
        ));

        JLabel lblStation = new JLabel();
        lblStation.setFont(lblStation.getFont().deriveFont(Font.BOLD));

        JLabel lblAmount = new JLabel();
        lblAmount.setFont(lblAmount.getFont().deriveFont(Font.BOLD));

        JPanel amountGroup = new JPanel(new BorderLayout(0, 3));
        amountGroup.setOpaque(false);
        amountGroup.setMaximumSize(new Dimension(160, 52));
        amountGroup.add(amountField, BorderLayout.CENTER);
        rateHint.setFont(rateHint.getFont().deriveFont(Font.ITALIC, 10f));
        rateHint.setForeground(new Color(120, 120, 120));
        amountGroup.add(rateHint, BorderLayout.SOUTH);

        addFundsBtn.putClientProperty("JButton.buttonType", "default");
        addFundsBtn.addActionListener(e -> onAddFunds());
        lockBtn.addActionListener(e -> onLockSelected());
        setTimeBtn.addActionListener(e -> onSetTime());

        panel.add(lblStation);
        panel.add(Box.createHorizontalStrut(8));
        panel.add(stationBox);
        panel.add(Box.createHorizontalStrut(24));
        panel.add(lblAmount);
        panel.add(Box.createHorizontalStrut(8));
        panel.add(amountGroup);
        panel.add(Box.createHorizontalStrut(12));
        panel.add(addFundsBtn);
        panel.add(Box.createHorizontalStrut(8));
        panel.add(lockBtn);
        panel.add(Box.createHorizontalStrut(8));
        panel.add(setTimeBtn);
        panel.add(Box.createHorizontalGlue());

        msg.onLocaleChange(l -> {
            lblStation.setText(msg.get("ctrl.station"));
            lblAmount.setText(msg.get("ctrl.amount"));
            addFundsBtn.setText(msg.get("ctrl.addfunds"));
            lockBtn.setText(msg.get("ctrl.lock"));
            setTimeBtn.setText(msg.get("ctrl.settime"));
            rateHint.setText(msg.get("ctrl.amount.hint"));
            setTitle(msg.get("app.title"));
        });
        return panel;
    }

    private void wireLocaleListener() {
        msg.onLocaleChange(l -> updateStatusBar());
    }

    // ---- Refresh -----------------------------------------------------------

    public void startRefresh() {
        int interval = cfg.intg("ui.refresh.interval.seconds", 2);
        refresher = new StationRefreshWorker(stationDao, tableModel, interval) {
            @Override
            protected void process(java.util.List<java.util.List<Station>> chunks) {
                super.process(chunks);
                refreshStationBox();
                updateStatusBar();
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

    // U4: fetch revenue off-EDT, update status bar when done
    private void updateStatusBar() {
        new SwingWorker<BigDecimal, Void>() {
            @Override protected BigDecimal doInBackground() throws Exception {
                return txDao.totalToday();
            }
            @Override protected void done() {
                try {
                    String revenue = String.format("%,.0f", get().doubleValue());
                    statusBar.setText(msg.format("status.bar", registry.size(), revenue));
                } catch (Exception e) {
                    statusBar.setText(msg.format("status.connected", registry.size()));
                }
            }
        }.execute();
    }

    // F1: reload today's transactions into the tx tab
    private void refreshTransactions() {
        new SwingWorker<List<CashTransaction>, Void>() {
            @Override protected List<CashTransaction> doInBackground() throws Exception {
                return txDao.listForToday();
            }
            @Override protected void done() {
                try {
                    List<CashTransaction> list = get();
                    txTableModel.setRowCount(0);
                    DateTimeFormatter fmt = DateTimeFormatter.ofPattern("HH:mm:ss");
                    for (CashTransaction t : list) {
                        txTableModel.addRow(new Object[]{
                            t.getTransactionId(),
                            t.getStationId(),
                            "₮" + t.getAmountPaid().toPlainString(),
                            t.getTimestamp().format(fmt)
                        });
                    }
                } catch (Exception ignored) {}
            }
        }.execute();
    }

    // ---- Actions -----------------------------------------------------------

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

        // Pricing: 5000 MNT = 60 min (3600 s). Truncate — never round up free time.
        final int secondsToAdd = amount
                .multiply(BigDecimal.valueOf(3600))
                .divide(BigDecimal.valueOf(5000), 0, RoundingMode.DOWN)
                .intValue();

        new SwingWorker<Boolean, Void>() {
            String errorMsg = null;
            @Override protected Boolean doInBackground() {
                try {
                    var maybe = registry.get(stationId);
                    if (maybe.isEmpty()) {
                        errorMsg = msg.format("dialog.fund.offline", stationId);
                        return false;
                    }
                    // Atomic: insert cash record + update time in one transaction.
                    // If station update fails, the transaction record also rolls back.
                    int totalSeconds = db.inTransaction(conn -> {
                        txDao.insert(stationId, amount, conn);
                        return stationDao.addTimeAndUnlock(stationId, secondsToAdd, conn);
                    });
                    maybe.get().send(Protocol.unlock(totalSeconds));
                    return true;
                } catch (Exception ex) {
                    errorMsg = ex.getMessage();
                    return false;
                }
            }
            @Override protected void done() {
                try {
                    if (get()) {
                        // U3: human-readable time in success dialog
                        JOptionPane.showMessageDialog(CashierFrame.this,
                            msg.format("dialog.fund.success", formatDuration(secondsToAdd), stationId));
                        amountField.setText("");
                        updateStatusBar();
                        if (tabbedPane.getSelectedIndex() == 1) refreshTransactions();
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

    // F2: set exact time in minutes via dialog
    private void onSetTime() {
        Integer stationId = (Integer) stationBox.getSelectedItem();
        if (stationId == null) {
            JOptionPane.showMessageDialog(this, msg.get("dialog.fund.noStation"));
            return;
        }
        String input = JOptionPane.showInputDialog(this,
            msg.format("dialog.settime.prompt", stationId));
        if (input == null) return; // cancelled
        final int minutes;
        try {
            minutes = Integer.parseInt(input.trim());
            if (minutes < 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, msg.get("dialog.settime.invalid"));
            return;
        }
        final int seconds = minutes * 60;
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception {
                stationDao.setTimeAndStatus(stationId, seconds);
                var maybe = registry.get(stationId);
                if (maybe.isPresent()) {
                    maybe.get().send(seconds > 0
                        ? Protocol.unlock(seconds)
                        : Protocol.CMD_LOCK);
                }
                return null;
            }
            @Override protected void done() {
                try {
                    get();
                    JOptionPane.showMessageDialog(CashierFrame.this,
                        msg.format("dialog.settime.success", stationId, minutes));
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(CashierFrame.this,
                        ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                }
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

    // U3: format seconds as human-readable duration string
    private static String formatDuration(int totalSeconds) {
        int h = totalSeconds / 3600;
        int m = (totalSeconds % 3600) / 60;
        int s = totalSeconds % 60;
        if (h > 0 && m > 0) return h + " hr " + m + " min";
        if (h > 0)           return h + " hr";
        if (m > 0 && s > 0) return m + " min " + s + " sec";
        if (m > 0)           return m + " min";
        return s + " sec";
    }
}
