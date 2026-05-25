package cafe.server.report;

import cafe.server.db.TransactionDao;
import cafe.shared.CashTransaction;

import com.lowagie.text.*;
import com.lowagie.text.pdf.*;

import java.awt.Color;
import java.io.File;
import java.io.FileOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Generates an end-of-shift report as a PDF file.
 *
 * <p>Replaces the old {@code ShiftReportMailer}. The cashier picks a save
 * location via {@code JFileChooser} in {@code CashierFrame}; this class
 * only handles document layout and writes bytes to the given {@link File}.
 */
public final class ShiftReportPdf {

    private static final Color COLOR_HEADER_BG = new Color(24, 58, 105);
    private static final Color COLOR_ROW_ALT   = new Color(240, 244, 252);
    private static final Color COLOR_BORDER    = new Color(180, 200, 230);
    private static final Color COLOR_TITLE     = new Color(24, 58, 105);
    private static final Color COLOR_TOTAL     = new Color(13, 80, 40);
    private static final Color COLOR_META      = new Color(100, 100, 100);

    private final TransactionDao txDao;

    public ShiftReportPdf(TransactionDao txDao) {
        this.txDao = txDao;
    }

    /**
     * Build the PDF from today's ledger and write it to {@code dest}.
     * Run from a background thread (SwingWorker), not the EDT.
     */
    public void generateTo(File dest) throws Exception {
        List<CashTransaction> txList = txDao.listForToday();
        BigDecimal total = txDao.totalToday();

        Document doc = new Document(PageSize.A4, 50, 50, 60, 60);
        try (FileOutputStream fos = new FileOutputStream(dest)) {
            PdfWriter.getInstance(doc, fos);
            doc.open();
            buildContent(doc, txList, total);
            doc.close();   // flush PDF trailer while fos is still open
        }
    }

    private void buildContent(Document doc, List<CashTransaction> txList, BigDecimal total)
            throws DocumentException {

        Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18, COLOR_TITLE);
        Font sectionFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13, COLOR_TITLE);
        Font headFont  = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, Color.WHITE);
        Font bodyFont  = FontFactory.getFont(FontFactory.HELVETICA,      10, Color.DARK_GRAY);
        Font totalFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, COLOR_TOTAL);
        Font metaFont  = FontFactory.getFont(FontFactory.HELVETICA,      10, COLOR_META);

        // ---- Title ----------------------------------------------------------
        Paragraph title = new Paragraph("PC Cafe — End of Shift Report", titleFont);
        title.setAlignment(Element.ALIGN_CENTER);
        doc.add(title);

        String dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        Paragraph meta = new Paragraph(
                "Date: " + dateStr + "     |     Transactions: " + txList.size(), metaFont);
        meta.setAlignment(Element.ALIGN_CENTER);
        meta.setSpacingBefore(4);
        meta.setSpacingAfter(18);
        doc.add(meta);

        // ---- Per-station summary --------------------------------------------
        Paragraph sec1 = new Paragraph("Per-Station Summary", sectionFont);
        sec1.setSpacingAfter(6);
        doc.add(sec1);

        PdfPTable summary = new PdfPTable(3);
        summary.setWidthPercentage(100);
        summary.setWidths(new float[]{1.5f, 2f, 2f});

        headerCell(summary, "Station",        headFont);
        headerCell(summary, "Transactions",   headFont);
        headerCell(summary, "Subtotal (MNT)", headFont);

        Map<Integer, List<CashTransaction>> byStation = new TreeMap<>();
        for (CashTransaction t : txList) {
            byStation.computeIfAbsent(t.getStationId(), k -> new ArrayList<>()).add(t);
        }

        if (byStation.isEmpty()) {
            PdfPCell empty = new PdfPCell(new Phrase("(no transactions today)", bodyFont));
            empty.setColspan(3);
            empty.setHorizontalAlignment(Element.ALIGN_CENTER);
            empty.setPadding(8);
            empty.setBorderColor(COLOR_BORDER);
            summary.addCell(empty);
        } else {
            boolean alt = false;
            for (Map.Entry<Integer, List<CashTransaction>> entry : byStation.entrySet()) {
                Color bg = alt ? COLOR_ROW_ALT : Color.WHITE;
                BigDecimal sub = entry.getValue().stream()
                        .map(CashTransaction::getAmountPaid)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                bodyCell(summary, String.valueOf(entry.getKey()),          bodyFont, bg, Element.ALIGN_CENTER);
                bodyCell(summary, String.valueOf(entry.getValue().size()), bodyFont, bg, Element.ALIGN_CENTER);
                bodyCell(summary, "MNT " + sub.toPlainString(),           bodyFont, bg, Element.ALIGN_RIGHT);
                alt = !alt;
            }
        }
        summary.setSpacingAfter(20);
        doc.add(summary);

        // ---- Full transaction log -------------------------------------------
        Paragraph sec2 = new Paragraph("Full Transaction Log", sectionFont);
        sec2.setSpacingAfter(6);
        doc.add(sec2);

        PdfPTable log = new PdfPTable(4);
        log.setWidthPercentage(100);
        log.setWidths(new float[]{1f, 1.5f, 2f, 2.5f});

        headerCell(log, "TXN #",      headFont);
        headerCell(log, "Station",    headFont);
        headerCell(log, "Amount (MNT)", headFont);
        headerCell(log, "Timestamp", headFont);

        DateTimeFormatter tf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        boolean alt = false;
        for (CashTransaction t : txList) {
            Color bg = alt ? COLOR_ROW_ALT : Color.WHITE;
            bodyCell(log, String.valueOf(t.getTransactionId()),       bodyFont, bg, Element.ALIGN_CENTER);
            bodyCell(log, String.valueOf(t.getStationId()),           bodyFont, bg, Element.ALIGN_CENTER);
            bodyCell(log, "MNT " + t.getAmountPaid().toPlainString(),bodyFont, bg, Element.ALIGN_RIGHT);
            bodyCell(log, t.getTimestamp().format(tf),               bodyFont, bg, Element.ALIGN_LEFT);
            alt = !alt;
        }
        log.setSpacingAfter(14);
        doc.add(log);

        // ---- Grand total ----------------------------------------------------
        Paragraph totalPara = new Paragraph(
                "TOTAL COLLECTED: MNT " + total.toPlainString(), totalFont);
        totalPara.setAlignment(Element.ALIGN_RIGHT);
        doc.add(totalPara);
    }

    // ---- Helpers ------------------------------------------------------------

    private static void headerCell(PdfPTable table, String text, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setBackgroundColor(COLOR_HEADER_BG);
        cell.setPadding(8);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        cell.setBorderColor(new Color(15, 40, 80));
        table.addCell(cell);
    }

    private static void bodyCell(PdfPTable table, String text, Font font, Color bg, int align) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setBackgroundColor(bg);
        cell.setPadding(6);
        cell.setHorizontalAlignment(align);
        cell.setBorderColor(COLOR_BORDER);
        table.addCell(cell);
    }
}
