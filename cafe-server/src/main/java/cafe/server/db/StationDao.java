package cafe.server.db;

import cafe.shared.Station;
import cafe.shared.StationStatus;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Persistence operations on the {@code stations} table.
 *
 * <p>Every public method opens and closes its own JDBC resources via
 * try-with-resources. Callers (TCP handlers, SwingWorker, etc.) must run
 * these methods OFF the Swing Event Dispatch Thread.
 */
public final class StationDao {

    private final Database db;

    public StationDao(Database db) { this.db = db; }

    /** Load every station, ordered by id. Used by the JTable refresh worker. */
    public List<Station> listAll() throws SQLException {
        String sql = "SELECT station_id, ip_address, status, time_remaining " +
                     "FROM stations ORDER BY station_id";
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            List<Station> out = new ArrayList<>();
            while (rs.next()) {
                out.add(map(rs));
            }
            return out;
        }
    }

    public Optional<Station> findById(int stationId) throws SQLException {
        String sql = "SELECT station_id, ip_address, status, time_remaining " +
                     "FROM stations WHERE station_id = ?";
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, stationId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    /**
     * Update the station's IP address and return its current state in one
     * connection — replaces the old separate {@code updateIpAddress} + {@code findById}
     * pair that opened two connections on reconnect.
     */
    public Optional<Station> updateIpAndFetch(int stationId, String ipAddress) throws SQLException {
        try (Connection c = db.open()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE stations SET ip_address = ? WHERE station_id = ?")) {
                ps.setString(1, ipAddress);
                ps.setInt(2, stationId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT station_id, ip_address, status, time_remaining " +
                    "FROM stations WHERE station_id = ?")) {
                ps.setInt(1, stationId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.empty();
                }
            }
        }
    }

    public void setStatus(int stationId, StationStatus status) throws SQLException {
        String sql = "UPDATE stations SET status = ? WHERE station_id = ?";
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, status.name());
            ps.setInt(2, stationId);
            ps.executeUpdate();
        }
    }

    /** Add {@code seconds} to the existing remaining time and unlock. Returns new total. */
    public int addTimeAndUnlock(int stationId, int secondsToAdd) throws SQLException {
        try (Connection c = db.open()) {
            return addTimeAndUnlock(stationId, secondsToAdd, c);
        }
    }

    /**
     * Same as {@link #addTimeAndUnlock(int, int)} but uses a caller-supplied
     * connection — for use inside {@link Database#inTransaction}.
     * Both the UPDATE and the confirming SELECT run on the same connection,
     * eliminating the cross-connection race condition.
     */
    public int addTimeAndUnlock(int stationId, int secondsToAdd, Connection c) throws SQLException {
        String update = "UPDATE stations " +
                        "SET time_remaining = time_remaining + ?, status = 'UNLOCKED' " +
                        "WHERE station_id = ?";
        try (PreparedStatement ps = c.prepareStatement(update)) {
            ps.setInt(1, secondsToAdd);
            ps.setInt(2, stationId);
            ps.executeUpdate();
        }
        String select = "SELECT time_remaining FROM stations WHERE station_id = ?";
        try (PreparedStatement ps = c.prepareStatement(select)) {
            ps.setInt(1, stationId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : secondsToAdd;
            }
        }
    }

    /** Set an exact time and derive status: UNLOCKED if seconds > 0, LOCKED if 0. */
    public void setTimeAndStatus(int stationId, int seconds) throws SQLException {
        int clamped = Math.max(0, seconds);
        String status = clamped > 0 ? "UNLOCKED" : "LOCKED";
        String sql = "UPDATE stations SET time_remaining = ?, status = ? WHERE station_id = ?";
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, clamped);
            ps.setString(2, status);
            ps.setInt(3, stationId);
            ps.executeUpdate();
        }
    }

    /** Zero the time and lock — fired on TIME_EXPIRED or manual lock. */
    public void zeroTimeAndLock(int stationId) throws SQLException {
        String sql = "UPDATE stations SET time_remaining = 0, status = 'LOCKED' " +
                     "WHERE station_id = ?";
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, stationId);
            ps.executeUpdate();
        }
    }

    private static Station map(ResultSet rs) throws SQLException {
        return new Station(
            rs.getInt("station_id"),
            rs.getString("ip_address"),
            StationStatus.parse(rs.getString("status")),
            rs.getInt("time_remaining")
        );
    }
}
