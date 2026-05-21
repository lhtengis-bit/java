package cafe.client;

import cafe.client.config.KioskConfig;
import cafe.client.net.ServerConnection;
import cafe.client.ui.KioskFrame;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Entry point for the kiosk client (Computer C). */
public final class Main {

    private static final Logger LOG = Logger.getLogger(Main.class.getName());

    public static void main(String[] args) throws Exception {
        KioskConfig cfg = KioskConfig.load();
        if (cfg.stationId() < 0) {
            System.err.println("station.id is missing from config.properties");
            System.exit(2);
        }

        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception e) {
                LOG.log(Level.WARNING, "L&F failed", e);
            }
            KioskFrame frame = new KioskFrame(cfg);
            ServerConnection conn = new ServerConnection(cfg, frame);
            frame.attach(conn);
            frame.setVisible(true);
            conn.start();
        });
    }
}
