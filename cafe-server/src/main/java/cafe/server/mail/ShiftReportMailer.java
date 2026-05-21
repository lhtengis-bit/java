package cafe.server.mail;

import cafe.server.config.ServerConfig;
import cafe.server.db.TransactionDao;
import cafe.shared.CashTransaction;

import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Properties;

/**
 * Sends an end-of-shift summary email built from the immutable ledger.
 *
 * <p>SMTP credentials are read from {@code config.properties} so they
 * never appear in source control.
 */
public final class ShiftReportMailer {

    private final ServerConfig cfg;
    private final TransactionDao txDao;

    public ShiftReportMailer(ServerConfig cfg, TransactionDao txDao) {
        this.cfg = cfg;
        this.txDao = txDao;
    }

    /** Build the report and send it. Run from a SwingWorker, not the EDT. */
    public void sendNow() throws Exception {
        List<CashTransaction> tx = txDao.listForToday();
        BigDecimal total = txDao.totalToday();

        String body = buildBody(tx, total);

        Properties props = new Properties();
        props.put("mail.smtp.host",            cfg.str("mail.smtp.host"));
        props.put("mail.smtp.port",            cfg.str("mail.smtp.port"));
        props.put("mail.smtp.auth",            cfg.str("mail.smtp.auth", "true"));
        props.put("mail.smtp.starttls.enable", cfg.str("mail.smtp.starttls.enable", "true"));

        final String user = cfg.str("mail.from");
        final String pass = cfg.str("mail.from.password");

        Session session = Session.getInstance(props, new Authenticator() {
            @Override protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(user, pass);
            }
        });

        MimeMessage msg = new MimeMessage(session);
        msg.setFrom(new InternetAddress(user));
        msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(cfg.str("mail.to")));
        msg.setSubject("PC Cafe — End of Shift Report (" +
            LocalDate.now().format(DateTimeFormatter.ISO_DATE) + ")");
        msg.setText(body, "UTF-8");

        Transport.send(msg);
    }

    private String buildBody(List<CashTransaction> tx, BigDecimal total) {
        StringBuilder sb = new StringBuilder();
        sb.append("End of Shift Report\n")
          .append("Date: ").append(LocalDate.now()).append("\n\n")
          .append(String.format("%-6s %-8s %-12s %-20s%n", "TXN", "STN", "AMOUNT", "TIME"));
        sb.append("--------------------------------------------------\n");
        DateTimeFormatter tf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        for (CashTransaction t : tx) {
            sb.append(String.format("%-6d %-8d %-12s %-20s%n",
                t.getTransactionId(),
                t.getStationId(),
                t.getAmountPaid().toPlainString(),
                t.getTimestamp().format(tf)));
        }
        sb.append("--------------------------------------------------\n");
        sb.append("Total cash collected: ").append(total.toPlainString()).append("\n");
        return sb.toString();
    }
}
