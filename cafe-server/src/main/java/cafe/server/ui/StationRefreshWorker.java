package cafe.server.ui;

import cafe.server.db.StationDao;
import cafe.shared.Station;

import javax.swing.SwingWorker;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SwingWorker that polls the stations table and pushes the result to a
 * {@link StationTableModel} via {@link #process}.
 *
 * <p>The blocking JDBC call lives in {@link #doInBackground()} on the
 * worker thread; UI mutations happen in {@link #process(java.util.List)}
 * on the EDT. This is the canonical Swing pattern.
 */
public  class StationRefreshWorker extends SwingWorker<Void, List<Station>> {

    private static final Logger LOG = Logger.getLogger(StationRefreshWorker.class.getName());

    private final StationDao dao;
    private final StationTableModel model;
    private final int intervalMs;
    private volatile boolean keepRunning = true;

    public StationRefreshWorker(StationDao dao, StationTableModel model, int intervalSeconds) {
        this.dao = dao;
        this.model = model;
        this.intervalMs = Math.max(500, intervalSeconds * 1000);
    }

    public void stop() { keepRunning = false; }

    @Override
    protected Void doInBackground() {
        while (keepRunning && !isCancelled()) {
            try {
                List<Station> snapshot = dao.listAll();
                publish(snapshot);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Station refresh failed", e);
            }
            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return null;
    }

    @Override
    protected void process(List<List<Station>> chunks) {
        // Only the most recent snapshot matters.
        List<Station> latest = chunks.get(chunks.size() - 1);
        model.replaceAll(latest);
    }
}
