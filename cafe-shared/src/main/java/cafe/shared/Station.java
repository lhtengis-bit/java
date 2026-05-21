package cafe.shared;

import java.util.Objects;

/**
 * Immutable snapshot of a row in the {@code stations} table.
 *
 * <p>Used by the JTable model and any cross-tier transfer.
 */
public final class Station {

    private final int stationId;
    private final String ipAddress;
    private final StationStatus status;
    private final int timeRemainingSeconds;

    public Station(int stationId,
                   String ipAddress,
                   StationStatus status,
                   int timeRemainingSeconds) {
        this.stationId = stationId;
        this.ipAddress = ipAddress;
        this.status = Objects.requireNonNull(status);
        this.timeRemainingSeconds = Math.max(0, timeRemainingSeconds);
    }

    public int getStationId()              { return stationId; }
    public String getIpAddress()           { return ipAddress; }
    public StationStatus getStatus()       { return status; }
    public int getTimeRemainingSeconds()   { return timeRemainingSeconds; }

    /** Convenience: format remaining time as HH:MM:SS for the UI. */
    public String getTimeRemainingPretty() {
        int s = timeRemainingSeconds;
        int h = s / 3600;
        int m = (s % 3600) / 60;
        int sec = s % 60;
        return String.format("%02d:%02d:%02d", h, m, sec);
    }

    @Override
    public String toString() {
        return "Station{" + stationId + ", " + status + ", " + getTimeRemainingPretty() + "}";
    }
}
