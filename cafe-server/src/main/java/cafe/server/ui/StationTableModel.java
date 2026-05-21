package cafe.server.ui;

import cafe.server.i18n.Messages;
import cafe.shared.Station;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link javax.swing.table.TableModel} backed by an in-memory list of
 * {@link Station} snapshots. Column headers are pulled from {@link Messages}
 * so they re-label when the locale toggles.
 *
 * <p>The reference Swing-style would use DefaultTableModel; we use an
 * AbstractTableModel because it gives us typed accessors per row (so the
 * UI can pull the selected Station object back out instead of poking at
 * column-by-column getValueAt calls).
 */
public final class StationTableModel extends AbstractTableModel {

    private final Messages msg;
    private final List<Station> rows = new ArrayList<>();

    public StationTableModel(Messages msg) {
        this.msg = msg;
        // Refresh headers whenever the language changes.
        msg.onLocaleChange(l -> fireTableStructureChanged());
    }

    public void replaceAll(List<Station> newRows) {
        rows.clear();
        rows.addAll(newRows);
        fireTableDataChanged();
    }

    public Station rowAt(int index) {
        return (index >= 0 && index < rows.size()) ? rows.get(index) : null;
    }

    @Override public int getRowCount()    { return rows.size(); }
    @Override public int getColumnCount() { return 4; }

    @Override
    public String getColumnName(int column) {
        return switch (column) {
            case 0 -> msg.get("table.col.station");
            case 1 -> msg.get("table.col.ip");
            case 2 -> msg.get("table.col.status");
            case 3 -> msg.get("table.col.time");
            default -> "";
        };
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        Station s = rows.get(rowIndex);
        return switch (columnIndex) {
            case 0 -> s.getStationId();
            case 1 -> s.getIpAddress();
            case 2 -> s.getStatus().name();
            case 3 -> s.getTimeRemainingPretty();
            default -> "";
        };
    }
}
