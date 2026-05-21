package cafe.server.net;

import cafe.server.db.StationDao;
import cafe.shared.Protocol;
import cafe.shared.StationStatus;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles one connected kiosk for the lifetime of its socket.
 *
 * <p>Runs on a thread pulled from the server's {@link java.util.concurrent.ExecutorService}.
 * Reads protocol lines, dispatches commands, and exposes a {@link #send}
 * method the rest of the engine uses to push UNLOCK / LOCK to this kiosk.
 */
public final class ClientHandler implements Runnable {

    private static final Logger LOG = Logger.getLogger(ClientHandler.class.getName());

    private final Socket socket;
    private final ClientRegistry registry;
    private final StationDao stationDao;

    private volatile int stationId = -1;   // negative until IDENTITY received
    private volatile PrintWriter out;
    private final Object writeLock = new Object();

    public ClientHandler(Socket socket, ClientRegistry registry, StationDao stationDao) {
        this.socket = socket;
        this.registry = registry;
        this.stationDao = stationDao;
    }

    public int getStationId() { return stationId; }

    @Override
    public void run() {
        try (BufferedReader in = new BufferedReader(
                 new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter writer = new PrintWriter(
                 new java.io.OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8),
                 /* autoFlush */ true)) {

            this.out = writer;

            String line;
            while ((line = in.readLine()) != null) {
                handleLine(line.trim());
            }
        } catch (SocketException se) {
            // The kiosk's cable was pulled, or the kiosk was force-killed.
            LOG.log(Level.INFO, "Kiosk station {0} disconnected: {1}",
                    new Object[]{stationId, se.getMessage()});
        } catch (IOException io) {
            LOG.log(Level.WARNING, "I/O error for station " + stationId, io);
        } finally {
            cleanup();
        }
    }

    private void handleLine(String line) {
        if (line.isEmpty()) return;

        String verb;
        String arg = "";
        int colon = line.indexOf(Protocol.SEP);
        if (colon >= 0) {
            verb = line.substring(0, colon);
            arg  = line.substring(colon + 1);
        } else {
            verb = line;
        }

        switch (verb) {
            case Protocol.CMD_IDENTITY     -> onIdentity(arg);
            case Protocol.CMD_TIME_EXPIRED -> onTimeExpired();
            case Protocol.CMD_PONG         -> { /* heartbeat acknowledged */ }
            default -> LOG.log(Level.WARNING, "Unknown verb from kiosk: {0}", line);
        }
    }

    private void onIdentity(String arg) {
        try {
            int id = Integer.parseInt(arg.trim());
            this.stationId = id;
            String ip = socket.getInetAddress().getHostAddress();

            stationDao.updateIpAddress(id, ip);
            registry.register(id, this);

            // Restore session if the kiosk reconnected mid-session with time remaining.
            // Check time only — status is OFFLINE after cleanup(), not UNLOCKED.
            var maybe = stationDao.findById(id);
            if (maybe.isPresent() && maybe.get().getTimeRemainingSeconds() > 0) {
                stationDao.setStatus(id, StationStatus.UNLOCKED);
                send(Protocol.unlock(maybe.get().getTimeRemainingSeconds()));
            } else {
                stationDao.zeroTimeAndLock(id);
                send(Protocol.CMD_LOCK);
            }

            LOG.log(Level.INFO, "Station {0} online from {1}", new Object[]{id, ip});
        } catch (NumberFormatException nfe) {
            LOG.log(Level.WARNING, "Bad IDENTITY payload: {0}", arg);
            closeQuietly();
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Failed to register kiosk", e);
            closeQuietly();
        }
    }

    private void onTimeExpired() {
        if (stationId < 0) return;
        try {
            stationDao.zeroTimeAndLock(stationId);
            send(Protocol.CMD_LOCK);
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Failed to lock station " + stationId + " on TIME_EXPIRED", e);
        }
    }

    /** Push a single line to this kiosk. Safe to call from any thread. */
    public void send(String message) {
        PrintWriter w = this.out;
        if (w == null) return;
        synchronized (writeLock) {
            w.print(message);
            w.print('\n');
            w.flush();
        }
    }

    public void closeQuietly() {
        try { socket.close(); } catch (IOException ignored) { /* fine */ }
    }

    private void cleanup() {
        if (stationId >= 0) {
            registry.unregister(stationId, this);
            try {
                stationDao.setStatus(stationId, StationStatus.OFFLINE);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Could not flag station " + stationId + " OFFLINE", e);
            }
        }
        closeQuietly();
    }
}
