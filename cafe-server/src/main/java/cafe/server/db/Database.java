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
        String u   = cfg.str("db.url");
        String usr = cfg.str("db.user");
        String pwd = cfg.str("db.password");
        if (u   == null || u.isBlank())   throw new IllegalStateException("db.url missing from config");
        if (usr == null || usr.isBlank()) throw new IllegalStateException("db.user missing from config");
        if (pwd == null)                  throw new IllegalStateException("db.password missing from config");
        this.url      = u;
        this.user     = usr;
        this.password = pwd;
    }

    public Connection open() throws SQLException {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException ignored) { /* SPI will handle it */ }
        return DriverManager.getConnection(url, user, password);
    }
}
