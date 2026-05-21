package cafe.client.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.logging.Logger;

/** Kiosk config loader — same resolution order as the server. */
public final class KioskConfig {

    private static final Logger LOG = Logger.getLogger(KioskConfig.class.getName());

    private final Properties props;

    private KioskConfig(Properties p) { this.props = p; }

    public static KioskConfig load() throws IOException {
        Properties p = new Properties();

        String override = System.getProperty("cafe.config");
        if (override != null) {
            try (InputStream in = Files.newInputStream(Path.of(override))) {
                p.load(in);
                LOG.info("Loaded kiosk config from system property: " + override);
                return new KioskConfig(p);
            }
        }

        Path cwd = Path.of("config.properties");
        if (Files.exists(cwd)) {
            try (InputStream in = Files.newInputStream(cwd)) {
                p.load(in);
                LOG.info("Loaded kiosk config from cwd: " + cwd.toAbsolutePath());
                return new KioskConfig(p);
            }
        }

        try (InputStream in = KioskConfig.class.getResourceAsStream("/config.properties")) {
            if (in == null) throw new IOException("config.properties not found");
            p.load(in);
            LOG.info("Loaded kiosk config from classpath (bundled default)");
        }
        return new KioskConfig(p);
    }

    public int    stationId()              { return parseInt(props.getProperty("station.id"), -1); }
    public String serverHost()             { return props.getProperty("server.host", "127.0.0.1"); }
    public int    serverPort()             { return parseInt(props.getProperty("server.port"), 6969); }
    public int    reconnectIntervalSec()   { return parseInt(props.getProperty("reconnect.interval.seconds"), 5); }

    private static int parseInt(String v, int fb) {
        if (v == null || v.isBlank()) return fb;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return fb; }
    }
}
