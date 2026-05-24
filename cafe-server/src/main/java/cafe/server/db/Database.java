package cafe.server.db;

import cafe.server.config.ServerConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * JDBC connection pool backed by HikariCP.
 *
 * <p>Callers use {@link #open()} to borrow a pooled connection and must
 * close it (try-with-resources) to return it to the pool. For operations
 * spanning multiple DAO calls, use {@link #inTransaction} to run them on
 * a single connection with explicit commit/rollback.
 *
 * <p>Call {@link #close()} exactly once on application shutdown to drain
 * the pool gracefully.
 */
public final class Database {

    /** Functional interface for lambdas that throw {@link SQLException}. */
    @FunctionalInterface
    public interface SqlFunction<T> {
        T apply(Connection c) throws SQLException;
    }

    private final HikariDataSource dataSource;

    public Database(ServerConfig cfg) {
        String url = cfg.str("db.url");
        String usr = cfg.str("db.user");
        String pwd = cfg.str("db.password");
        if (url == null || url.isBlank()) throw new IllegalStateException("db.url missing from config");
        if (usr == null || usr.isBlank()) throw new IllegalStateException("db.user missing from config");
        if (pwd == null)                  throw new IllegalStateException("db.password missing from config");

        HikariConfig hk = new HikariConfig();
        hk.setJdbcUrl(url);
        hk.setUsername(usr);
        hk.setPassword(pwd);
        hk.setMaximumPoolSize(cfg.intg("db.pool.max", 10));
        hk.setMinimumIdle(cfg.intg("db.pool.min.idle", 2));
        hk.setIdleTimeout(cfg.intg("db.pool.idle.timeout.ms", 30_000));
        hk.setConnectionTestQuery("SELECT 1");
        hk.setPoolName("CafePool");

        this.dataSource = new HikariDataSource(hk);
    }

    /** Borrow a connection from the pool. Must be closed by the caller. */
    public Connection open() throws SQLException {
        return dataSource.getConnection();
    }

    /**
     * Run {@code fn} inside a single JDBC transaction on one connection.
     * Commits on success; rolls back and re-throws on {@link SQLException}
     * or any {@link RuntimeException} from the lambda.
     */
    public <T> T inTransaction(SqlFunction<T> fn) throws SQLException {
        try (Connection c = open()) {
            c.setAutoCommit(false);
            try {
                T result = fn.apply(c);
                c.commit();
                return result;
            } catch (Exception e) {
                try { c.rollback(); } catch (SQLException re) { e.addSuppressed(re); }
                if (e instanceof SQLException se) throw se;
                if (e instanceof RuntimeException re) throw re;
                throw new SQLException("Unexpected error in transaction", e);
            }
        }
    }

    /** Drain the pool — call once on application shutdown. */
    public void close() {
        dataSource.close();
    }
}
