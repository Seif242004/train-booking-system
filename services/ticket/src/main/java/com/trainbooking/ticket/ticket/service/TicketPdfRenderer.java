package com.trainbooking.ticket.ticket.service;

import com.lowagie.text.BadElementException;
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
import java.io.InputStream;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger LOG = LoggerFactory.getLogger(TicketPdfRenderer.class);

    /**
     * The ONE place the PDF takes its logo from: a PNG on the classpath (at least 1000 px wide, so it stays sharp when printed). OpenPDF cannot draw
     * an SVG and no SVG library is added, so the official file is dropped here as a PNG; swapping the logo is a change of this file only.
     */
    public static final String LOGO_RESOURCE = "brand/logo-red.png";

    /** How wide the logo is drawn on the page, in points (the PNG has about 3 times as many pixels). */
    private static final float LOGO_WIDTH = 110;

    private final ZoneId zone;
    private final byte[] logo;

    public TicketPdfRenderer(ZoneId zone) {
        this(zone, loadLogo(LOGO_RESOURCE));
    }

    /** {@code logo} is the PNG bytes, or null for the plain header (the layout is valid with and without it). */
    public TicketPdfRenderer(ZoneId zone, byte[] logo) {
        this.zone = zone;
        this.logo = logo;
    }

    /** The logo resource, or null with a warning when it is missing: a ticket without the logo is better than no ticket. */
    public static byte[] loadLogo(String resource) {
        try (InputStream in = TicketPdfRenderer.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                LOG.warn("The ticket logo {} is not on the classpath: the PDF is drawn with the plain header", resource);
                return null;
            }
            return in.readAllBytes();
        } catch (IOException e) {
            LOG.warn("The ticket logo {} could not be read: the PDF is drawn with the plain header", resource, e);
            return null;
        }
    }

    public byte[] render(TicketRequest ticket, String qrToken) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A5, 36, 36, 36, 36);
        try {
            PdfWriter.getInstance(document, out);
            document.open();

            addLogo(document);
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
            Image qr = Image.getInstance(QrImages.png(qrToken, QrImages.PRINT_SIZE));
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

    /** The logo at the top of the page, with its own aspect ratio. A logo that cannot be read is skipped, never fatal. */
    private void addLogo(Document document) throws DocumentException {
        if (logo == null) {
            return;
        }
        try {
            Image image = Image.getInstance(logo);
            image.scaleToFit(LOGO_WIDTH, LOGO_WIDTH * 0.6f);
            image.setAlignment(Element.ALIGN_LEFT);
            image.setSpacingAfter(8);
            document.add(image);
        } catch (IOException | BadElementException e) {
            LOG.warn("The ticket logo could not be drawn: the PDF is drawn with the plain header", e);
        }
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
