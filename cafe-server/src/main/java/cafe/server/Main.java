package cafe.server;

import cafe.server.config.ServerConfig;
import cafe.server.db.Database;
import cafe.server.db.StationDao;
import cafe.server.db.TransactionDao;
import cafe.server.i18n.Messages;
import cafe.server.net.CafeServer;
import cafe.server.net.ClientRegistry;
import cafe.server.ui.CashierFrame;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Entry point for the Central Management Engine (Computer B).
 *
 * <p>Wires the dependency graph: config -> DB -> DAOs -> registry ->
 * TCP server (background thread) -> Cashier UI (EDT).
 */
public final class Main {

    private static final Logger LOG = Logger.getLogger(Main.class.getName());

    public static void main(String[] args) throws Exception {
        ServerConfig cfg = ServerConfig.load();

        Database db = new Database(cfg);
        StationDao stationDao = new StationDao(db);
        TransactionDao txDao  = new TransactionDao(db);

        ClientRegistry registry = new ClientRegistry();

        // Start TCP server BEFORE the UI so kiosks can connect immediately.
        CafeServer server = new CafeServer(cfg, registry, stationDao);
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "cafe-shutdown"));

        Locale initial = parseLocale(cfg.str("ui.default.locale", "en"));
        Messages msg = new Messages(initial);

        SwingUtilities.invokeLater(() -> {
            try {
                com.formdev.flatlaf.FlatLightLaf.setup();
            } catch (Exception e) {
                LOG.log(Level.WARNING, "FlatLaf L&F failed", e);
                try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
                catch (Exception ignored) { }
            }
            CashierFrame frame = new CashierFrame(cfg, msg, stationDao, txDao, registry);
            frame.setVisible(true);
            frame.startRefresh();
        });
    }

    private static Locale parseLocale(String code) {
        if (code == null || code.isBlank()) return Locale.ENGLISH;
        return switch (code.toLowerCase()) {
            case "mn" -> new Locale("mn");
            default   -> Locale.ENGLISH;
        };
    }
}
