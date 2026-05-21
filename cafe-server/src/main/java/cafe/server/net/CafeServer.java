package cafe.server.net;

import cafe.server.config.ServerConfig;
import cafe.server.db.StationDao;
import cafe.shared.Protocol;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The TCP accept loop. Lives on its own daemon thread so it never blocks
 * the Swing Event Dispatch Thread.
 *
 * <p>Pulls connections off {@link ServerSocket#accept()}, hands each one
 * to a {@link ClientHandler} on a fixed thread pool, and runs a periodic
 * heartbeat that broadcasts PING.
 */
public final class CafeServer {

    private static final Logger LOG = Logger.getLogger(CafeServer.class.getName());

    private final ServerConfig cfg;
    private final ClientRegistry registry;
    private final StationDao stationDao;

    private ServerSocket serverSocket;
    private ExecutorService workers;
    private ScheduledExecutorService heartbeat;
    private Thread acceptThread;
    private volatile boolean running;

    public CafeServer(ServerConfig cfg, ClientRegistry registry, StationDao stationDao) {
        this.cfg = cfg;
        this.registry = registry;
        this.stationDao = stationDao;
    }

    public void start() throws IOException {
        int port = cfg.intg("server.port", Protocol.DEFAULT_PORT);
        String host = cfg.str("server.bind.host", "0.0.0.0");
        int poolSize = cfg.intg("server.thread.pool.size", 16);
        int hb = cfg.intg("server.heartbeat.interval.seconds", 15);

        this.serverSocket = new ServerSocket();
        this.serverSocket.bind(new InetSocketAddress(host, port));

        this.workers   = Executors.newFixedThreadPool(poolSize, r -> {
            Thread t = new Thread(r, "cafe-handler");
            t.setDaemon(true);
            return t;
        });
        this.heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cafe-heartbeat");
            t.setDaemon(true);
            return t;
        });

        this.running = true;
        this.acceptThread = new Thread(this::acceptLoop, "cafe-accept");
        this.acceptThread.setDaemon(true);
        this.acceptThread.start();

        this.heartbeat.scheduleAtFixedRate(this::pingAll, hb, hb, TimeUnit.SECONDS);

        LOG.log(Level.INFO, "Cafe server listening on {0}:{1}", new Object[]{host, port});
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                ClientHandler handler = new ClientHandler(socket, registry, stationDao);
                workers.submit(handler);
            } catch (IOException e) {
                if (running) {
                    LOG.log(Level.WARNING, "accept() failed", e);
                }
            }
        }
    }

    private void pingAll() {
        // Iteration over ConcurrentHashMap is safe and weakly consistent.
        // We rely on socket-level exceptions to evict dead connections.
        try {
            for (var s : stationDao.listAll()) {
                registry.get(s.getStationId())
                        .ifPresent(h -> h.send(Protocol.CMD_PING));
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Heartbeat sweep failed", e);
        }
    }

    public void stop() {
        running = false;
        registry.broadcast(Protocol.CMD_LOCK); // graceful: lock all kiosks before closing
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
        if (workers   != null) workers.shutdownNow();
        if (heartbeat != null) heartbeat.shutdownNow();
    }
}
