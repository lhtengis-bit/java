package cafe.server.net;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe registry mapping station id -> active {@link ClientHandler}.
 *
 * <p>Backed by {@link ConcurrentHashMap}, so reads from the UI thread and
 * writes from socket handlers can interleave without explicit locking.
 */
public final class ClientRegistry {

    private final ConcurrentHashMap<Integer, ClientHandler> handlers = new ConcurrentHashMap<>();

    /**
     * Register a new handler for a station id. If a previous socket existed
     * (e.g. the kiosk rebooted), close it first to free the resource.
     */
    public void register(int stationId, ClientHandler handler) {
        ClientHandler previous = handlers.put(stationId, handler);
        if (previous != null && previous != handler) {
            previous.closeQuietly();
        }
    }

    public void unregister(int stationId, ClientHandler handler) {
        // Only remove if the value still matches — avoids a race where a
        // newer reconnecting handler gets accidentally evicted.
        handlers.remove(stationId, handler);
    }

    public Optional<ClientHandler> get(int stationId) {
        return Optional.ofNullable(handlers.get(stationId));
    }

    public int size() { return handlers.size(); }
}
