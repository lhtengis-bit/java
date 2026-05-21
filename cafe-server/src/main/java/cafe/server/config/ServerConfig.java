package cafe.server.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Loads server configuration from {@code config.properties}.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>System property {@code -Dcafe.config=/path/to/file.properties}</li>
 *   <li>{@code ./config.properties} next to the JAR (cwd)</li>
 *   <li>{@code /config.properties} on the classpath (bundled default)</li>
 * </ol>
 */
public final class ServerConfig {

    private final Properties props;

    private ServerConfig(Properties p) { this.props = p; }

    public static ServerConfig load() throws IOException {
        Properties p = new Properties();

        String override = System.getProperty("cafe.config");
        if (override != null) {
            try (InputStream in = Files.newInputStream(Path.of(override))) {
                p.load(in);
                return new ServerConfig(p);
            }
        }

        Path cwd = Path.of("config.properties");
        if (Files.exists(cwd)) {
            try (InputStream in = Files.newInputStream(cwd)) {
                p.load(in);
                return new ServerConfig(p);
            }
        }

        try (InputStream in = ServerConfig.class.getResourceAsStream("/config.properties")) {
            if (in == null) {
                throw new IOException("config.properties not found on classpath");
            }
            p.load(in);
        }
        return new ServerConfig(p);
    }

    public String  str (String key)                   { return props.getProperty(key); }
    public String  str (String key, String fallback)  { return props.getProperty(key, fallback); }
    public int     intg(String key, int fallback)     { return parseInt(props.getProperty(key), fallback); }

    private static int parseInt(String v, int fallback) {
        if (v == null || v.isBlank()) return fallback;
        try { return Integer.parseInt(v.trim()); }
        catch (NumberFormatException e) { return fallback; }
    }

    public Properties raw() { return props; }
}
