package cafe.shared;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Immutable snapshot of a row in the append-only {@code cash_transactions}
 * ledger. Used by the end-of-shift email report.
 *
 * <p>{@code BigDecimal} preserves the exact monetary value (no float drift).
 */
public final class CashTransaction {

    private final int transactionId;
    private final int stationId;
    private final BigDecimal amountPaid;
    private final LocalDateTime timestamp;

    public CashTransaction(int transactionId,
                           int stationId,
                           BigDecimal amountPaid,
                           LocalDateTime timestamp) {
        this.transactionId = transactionId;
        this.stationId = stationId;
        this.amountPaid = Objects.requireNonNull(amountPaid);
        this.timestamp = Objects.requireNonNull(timestamp);
    }

    public int getTransactionId()       { return transactionId; }
    public int getStationId()           { return stationId; }
    public BigDecimal getAmountPaid()   { return amountPaid; }
    public LocalDateTime getTimestamp() { return timestamp; }
}
