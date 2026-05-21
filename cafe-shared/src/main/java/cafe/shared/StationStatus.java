package cafe.shared;

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

    /** Parse a value from the DB. Falls back to OFFLINE if unrecognised. */
    public static StationStatus parse(String raw) {
        if (raw == null) return OFFLINE;
        try {
            return StationStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return OFFLINE;
        }
    }
}
