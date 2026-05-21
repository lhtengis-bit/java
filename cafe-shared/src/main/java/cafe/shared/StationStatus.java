package cafe.shared;

import java.util.logging.Logger;

/**
 * The three legal states for a kiosk station.
 *
 * <p>These string values must match the {@code CHECK} constraint on
 * {@code stations.status} in MySQL.
 */
public enum StationStatus {
    LOCKED,
    UNLOCKED,
    OFFLINE;

    private static final Logger LOG = Logger.getLogger(StationStatus.class.getName());

    /** Parse a value from the DB. Falls back to OFFLINE if unrecognised. */
    public static StationStatus parse(String raw) {
        if (raw == null) return OFFLINE;
        try {
            return StationStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            LOG.warning("Unknown station status in DB: '" + raw + "' — defaulting to OFFLINE");
            return OFFLINE;
        }
    }
}
