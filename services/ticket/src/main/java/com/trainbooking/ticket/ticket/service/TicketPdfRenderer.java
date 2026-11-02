package com.trainbooking.ticket.ticket.service;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.trainbooking.ticket.ticket.model.TicketRequest;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Draws the PDF of a ticket (SRS FR-T2): booking reference, passenger, train, date, origin and destination with times, the coach and
 * seat numbers or standing places, the price paid, and the QR code. It has no state, so it is tested without a database.
 */
public class TicketPdfRenderer {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);

    private static final Font TITLE = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 20, new Color(0x11, 0x2B, 0x4A));
    private static final Font REFERENCE = FontFactory.getFont(FontFactory.COURIER_BOLD, 22);
    private static final Font LABEL = FontFactory.getFont(FontFactory.HELVETICA, 9, Color.GRAY);
    private static final Font TEXT = FontFactory.getFont(FontFactory.HELVETICA, 11);
    private static final Font BOLD = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11);
    private static final Font SMALL = FontFactory.getFont(FontFactory.HELVETICA, 8, Color.GRAY);

    private final ZoneId zone;

    public TicketPdfRenderer(ZoneId zone) {
        this.zone = zone;
    }

    public byte[] render(TicketRequest ticket, String qrToken) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A5, 36, 36, 36, 36);
        try {
            PdfWriter.getInstance(document, out);
            document.open();

            document.add(new Paragraph("Train ticket", TITLE));
            document.add(labelled("BOOKING REFERENCE", ticket.reference(), REFERENCE));

            PdfPTable head = new PdfPTable(new float[] {3, 2});
            head.setWidthPercentage(100);
            PdfPCell left = noBorder();
            left.addElement(labelled("PASSENGER", ticket.passengerName(), BOLD));
            String train = ticket.trainNumber() + (ticket.trainName() == null || ticket.trainName().isBlank() ? "" : " " + ticket.trainName());
            left.addElement(labelled("TRAIN", train, TEXT));
            left.addElement(labelled("DATE", DATE.format(ticket.departure().atZoneSameInstant(zone)), TEXT));
            left.addElement(labelled("FROM", ticket.fromStation() + "  " + TIME.format(ticket.departure().atZoneSameInstant(zone)), BOLD));
            left.addElement(labelled("TO", ticket.toStation() + "  " + TIME.format(ticket.arrival().atZoneSameInstant(zone)), BOLD));
            head.addCell(left);

            PdfPCell right = noBorder();
            Image qr = Image.getInstance(QrImages.png(qrToken, 260));
            qr.scaleToFit(120, 120);
            qr.setAlignment(Element.ALIGN_CENTER);
            right.addElement(qr);
            Paragraph caption = new Paragraph("Show this code at the gate", SMALL);
            caption.setAlignment(Element.ALIGN_CENTER);
            right.addElement(caption);
            head.addCell(right);
            head.setSpacingBefore(10);
            document.add(head);

            PdfPTable lines = new PdfPTable(new float[] {2, 3, 2});
            lines.setWidthPercentage(100);
            lines.setSpacingBefore(12);
            lines.addCell(header("Coach"));
            lines.addCell(header("Seat or standing"));
            lines.addCell(header("Price"));
            for (TicketRequest.Line line : ticket.lines()) {
                lines.addCell(cell(line.coachCode() + " (" + line.coachClass().toLowerCase(Locale.ENGLISH) + ")", Element.ALIGN_LEFT));
                String what = "SEAT".equals(line.type())
                        ? "Seat " + line.seatNumber()
                        : line.qty() + " standing " + (line.qty() == 1 ? "place" : "places");
                lines.addCell(cell(what, Element.ALIGN_LEFT));
                lines.addCell(cell(line.price().toPlainString() + " " + ticket.currency(), Element.ALIGN_RIGHT));
            }
            document.add(lines);

            Paragraph total = new Paragraph("Total paid  " + ticket.amount().toPlainString() + " " + ticket.currency(), BOLD);
            total.setAlignment(Element.ALIGN_RIGHT);
            total.setSpacingBefore(8);
            document.add(total);

            Paragraph note = new Paragraph("This ticket is valid while the booking is confirmed. If the booking is cancelled, the code on it stops working.", SMALL);
            note.setSpacingBefore(16);
            document.add(note);
            document.close();
        } catch (DocumentException | IOException e) {
            throw new IllegalStateException("The ticket PDF could not be made.", e);
        }
        return out.toByteArray();
    }

    private static Paragraph labelled(String label, String value, Font valueFont) {
        Paragraph paragraph = new Paragraph();
        paragraph.setSpacingBefore(4);
        paragraph.add(new Phrase(label + "\n", LABEL));
        paragraph.add(new Phrase(value, valueFont));
        return paragraph;
    }

    private static PdfPCell noBorder() {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        return cell;
    }

    private static PdfPCell header(String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text, LABEL));
        cell.setBorder(Rectangle.BOTTOM);
        cell.setPaddingBottom(4);
        return cell;
    }

    private static PdfPCell cell(String text, int align) {
        PdfPCell cell = new PdfPCell(new Phrase(text, TEXT));
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setHorizontalAlignment(align);
        cell.setPaddingTop(3);
        cell.setPaddingBottom(3);
        return cell;
    }
}
