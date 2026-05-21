package cafe.client.net;

import cafe.client.config.KioskConfig;
import cafe.shared.Protocol;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Owns the kiosk's TCP connection to the cafe server.
 *
 * <p>Runs its own thread. On disconnect it sleeps for the configured
 * interval and reconnects automatically — clients are stateless, so all
 * we need is to re-announce IDENTITY and the server tells us what state
 * we should be in.
 */
public final class ServerConnection implements Runnable {

    private static final Logger LOG = Logger.getLogger(ServerConnection.class.getName());

    public interface Listener {
        /** Called on the EDT. */ void onUnlock(int seconds);
        /** Called on the EDT. */ void onLock();
    }

    private final KioskConfig cfg;
    private final Listener listener;

    private volatile Socket socket;
    private volatile PrintWriter out;
    private volatile boolean running = true;

    public ServerConnection(KioskConfig cfg, Listener listener) {
        this.cfg = cfg;
        this.listener = listener;
    }

    public void start() {
        Thread t = new Thread(this, "kiosk-net");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void run() {
        while (running) {
            try {
                connectOnce();
            } catch (IOException io) {
                LOG.log(Level.WARNING, "Connection lost: {0}", io.getMessage());
            }
            if (!running) break;
            sleepQuietly(cfg.reconnectIntervalSec() * 1000L);
        }
    }

    private void connectOnce() throws IOException {
        socket = new Socket(cfg.serverHost(), cfg.serverPort());
        out = new PrintWriter(
            new java.io.OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8),
            /* autoFlush */ true);

        // Announce identity. Server replies with LOCK or UNLOCK:<seconds>.
        send(Protocol.identity(cfg.stationId()));

        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                dispatch(line.trim());
            }
        } finally {
            closeSocket();
        }
    }

    private void dispatch(String line) {
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
            case Protocol.CMD_UNLOCK -> {
                int secs;
                try { secs = Integer.parseInt(arg.trim()); }
                catch (NumberFormatException e) { secs = 0; }
                final int finalSecs = secs;
                javax.swing.SwingUtilities.invokeLater(() -> listener.onUnlock(finalSecs));
            }
            case Protocol.CMD_LOCK -> javax.swing.SwingUtilities.invokeLater(listener::onLock);
            case Protocol.CMD_PING -> send(Protocol.CMD_PONG);
            default -> LOG.log(Level.WARNING, "Unknown command from server: {0}", line);
        }
    }

    public void send(String message) {
        PrintWriter w = this.out;
        if (w == null) return;
        synchronized (w) {
            w.print(message);
            w.print('\n');
            w.flush();
        }
    }

    public void stop() {
        running = false;
        closeSocket();
    }

    private void closeSocket() {
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        socket = null;
        out = null;
    }

    private void sleepQuietly(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }
}
