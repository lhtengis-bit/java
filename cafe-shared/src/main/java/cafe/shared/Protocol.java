package cafe.shared;

/**
 * Wire protocol constants shared between the central server (Computer B)
 * and kiosk clients (Computer C).
 *
 * <p>The protocol is a simple line-based text protocol over TCP. Each
 * message is a single UTF-8 line terminated by '\n'. A message has the
 * form {@code VERB} or {@code VERB:ARG}.
 *
 * <p>Wire examples:
 * <pre>
 *   Client -> Server   IDENTITY:101
 *   Server -> Client   UNLOCK:3600
 *   Server -> Client   LOCK
 *   Server -> Client   PING
 *   Client -> Server   PONG
 *   Client -> Server   TIME_EXPIRED
 * </pre>
 */
public final class Protocol {

    private Protocol() { }

    /** Default TCP port for the cafe server. */
    public static final int DEFAULT_PORT = 8080;

    /** Field separator inside a single message line. */
    public static final String SEP = ":";

    // ---- Client -> Server -------------------------------------------------

    /** First message a kiosk sends on connect: {@code IDENTITY:<stationId>}. */
    public static final String CMD_IDENTITY     = "IDENTITY";

    /** Kiosk reports that its local countdown reached zero. */
    public static final String CMD_TIME_EXPIRED = "TIME_EXPIRED";

    /** Kiosk reply to a server heartbeat. */
    public static final String CMD_PONG         = "PONG";

    // ---- Server -> Client -------------------------------------------------

    /** Server grants paid time: {@code UNLOCK:<seconds>}. */
    public static final String CMD_UNLOCK       = "UNLOCK";

    /** Server forces the kiosk to the locked screen. */
    public static final String CMD_LOCK         = "LOCK";

    /** Server heartbeat. Kiosk must answer with PONG. */
    public static final String CMD_PING         = "PING";

    // ---- helpers ----------------------------------------------------------

    public static String unlock(int seconds) {
        return CMD_UNLOCK + SEP + seconds;
    }

    public static String identity(int stationId) {
        return CMD_IDENTITY + SEP + stationId;
    }
}
