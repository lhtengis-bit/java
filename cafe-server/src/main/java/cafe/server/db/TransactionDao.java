package cafe.server.db;

import cafe.shared.CashTransaction;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Append-only DAO for {@code cash_transactions}.
 *
 * <p>By design this class exposes <b>INSERT and SELECT only</b>. The DB
 * trigger {@code block_transaction_updates} guarantees integrity even if
 * a future developer adds a forbidden method here.
 */
public final class TransactionDao {

    private final Database db;

    public TransactionDao(Database db) { this.db = db; }

    /** Record a cash deposit. Returns the generated transaction id. */
    public int insert(int stationId, BigDecimal amountPaid) throws SQLException {
        try (Connection c = db.open()) {
            return insert(stationId, amountPaid, c);
        }
    }

    /**
     * Same as {@link #insert(int, BigDecimal)} but uses a caller-supplied
     * connection — for use inside {@link Database#inTransaction}.
     */
    public int insert(int stationId, BigDecimal amountPaid, Connection c) throws SQLException {
        String sql = "INSERT INTO cash_transactions (station_id, amount_paid) VALUES (?, ?)";
        try (PreparedStatement ps = c.prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, stationId);
            ps.setBigDecimal(2, amountPaid);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getInt(1) : -1;
            }
        }
    }

    /** Today's transactions, oldest first. Used by the end-of-shift report. */
    public List<CashTransaction> listForToday() throws SQLException {
        // Range condition is sargable — can use an index on `timestamp`.
        // DATE(timestamp) = CURDATE() wraps the column in a function and prevents index use.
        String sql = "SELECT transaction_id, station_id, amount_paid, `timestamp` " +
                     "FROM cash_transactions " +
                     "WHERE `timestamp` >= CURDATE() AND `timestamp` < CURDATE() + INTERVAL 1 DAY " +
                     "ORDER BY `timestamp`";
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            List<CashTransaction> out = new ArrayList<>();
            while (rs.next()) {
                Timestamp ts = rs.getTimestamp("timestamp");
                out.add(new CashTransaction(
                    rs.getInt("transaction_id"),
                    rs.getInt("station_id"),
                    rs.getBigDecimal("amount_paid"),
                    ts != null ? ts.toLocalDateTime() : LocalDateTime.now(ZoneOffset.UTC)
                ));
            }
            return out;
        }
    }

    /** Sum of today's cash. Returns BigDecimal.ZERO if nothing recorded. */
    public BigDecimal totalToday() throws SQLException {
        String sql = "SELECT COALESCE(SUM(amount_paid), 0) AS total FROM cash_transactions " +
                     "WHERE `timestamp` >= CURDATE() AND `timestamp` < CURDATE() + INTERVAL 1 DAY";
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getBigDecimal("total") : BigDecimal.ZERO;
        }
    }
}
