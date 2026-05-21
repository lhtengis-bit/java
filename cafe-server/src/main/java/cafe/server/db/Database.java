package cafe.server.db;

import cafe.server.config.ServerConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Lightweight JDBC connection factory.
 *
 * <p>For a 2nd-year project we deliberately keep this simple: open a new
 * connection per DAO call. If you want a proper pool later, swap in HikariCP
 * here without touching any DAO.
 */
public final class Database {

    private final String url;
    private final String user;
    private final String password;

    public Database(ServerConfig cfg) {
        this.url      = cfg.str("db.url");
        this.user     = cfg.str("db.user");
        this.password = cfg.str("db.password");
    }

    public Connection open() throws SQLException {
        // The MySQL driver registers itself via SPI on Java 9+, but loading
        // it explicitly is a harmless safety net for older classpaths.
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException ignored) { /* SPI will handle it */ }
        return DriverManager.getConnection(url, user, password);
    }
}
