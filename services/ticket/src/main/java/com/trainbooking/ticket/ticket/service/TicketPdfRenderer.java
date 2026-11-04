package com.trainbooking.ticket.ticket.service;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfWriter;
import com.trainbooking.ticket.ticket.model.TicketRequest;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.text.Normalizer;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Draws the PDF of a ticket (SRS FR-T2) as a print of the boarding pass on the ticket page: ONE A5 landscape page, a white card with a red
 * logo and "Train ticket" on top, the route with big times, a grid of facts, a dashed perforation with notches, the QR code panel with the
 * booking reference, and a footer with the payment line and the validity note. Everything is placed by absolute coordinates, text is real text
 * (it can be selected and extracted), fonts are the built-in Helvetica and Courier families (WinAnsi), and every text is made safe for that
 * encoding first, so no name can break the rendering. It has no state, so it is tested without a database.
 */
public class TicketPdfRenderer {

    private static final Logger LOG = LoggerFactory.getLogger(TicketPdfRenderer.class);

    /**
     * The ONE place the PDF takes its logo from: a PNG on the classpath (at least 1000 px wide, so it stays sharp when printed). OpenPDF cannot draw
     * an SVG and no SVG library is added, so the official file is dropped here as a PNG; swapping the logo is a change of this file only.
     */
    public static final String LOGO_RESOURCE = "brand/logo-red.png";

    // The design tokens of the page (docs/design/DESIGN.md).
    private static final Color RED = new Color(0xE1, 0x08, 0x01);
    private static final Color INK = new Color(0x19, 0x13, 0x29);
    private static final Color INK_2 = new Color(0x3B, 0x34, 0x50);
    private static final Color MUTED = new Color(0x4A, 0x43, 0x60);
    private static final Color LINE = new Color(0xDC, 0xD8, 0xE2);
    private static final Color LINE_SOFT = new Color(0xEE, 0xEB, 0xF2);

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);

    // A5 landscape is 595 x 420 pt (210 x 148 mm); the card sits 10 mm from the edges.
    private static final float MM = 72f / 25.4f;
    private static final float MARGIN = 10 * MM;
    private static final float PAD = 20;
    private static final float HEADER = 58;
    private static final float FOOTER = 66;
    private static final float LOGO_WIDTH = 34 * MM;
    /** The logo PNG has transparent margins around the drawing; the logo is moved left by this share of its width so the drawing meets the card padding. */
    private static final float LOGO_INSET = 0.05f;
    private static final float QR_SIZE = 44 * MM;

    private static final CharsetEncoder WIN_ANSI = Charset.forName("windows-1252").newEncoder();

    private final ZoneId zone;
    private final byte[] logo;
    private final BaseFont regular;
    private final BaseFont bold;
    private final BaseFont mono;

    public TicketPdfRenderer(ZoneId zone) {
        this(zone, loadLogo(LOGO_RESOURCE));
    }

    /** {@code logo} is the PNG bytes, or null for the plain header (the layout is valid with and without it). */
    public TicketPdfRenderer(ZoneId zone, byte[] logo) {
        this.zone = zone;
        this.logo = logo;
        try {
            this.regular = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
            this.bold = BaseFont.createFont(BaseFont.HELVETICA_BOLD, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
            this.mono = BaseFont.createFont(BaseFont.COURIER_BOLD, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
        } catch (DocumentException | IOException e) {
            throw new IllegalStateException("The built-in PDF fonts could not be loaded.", e);
        }
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
        Document document = new Document(new Rectangle(PageSize.A5.getHeight(), PageSize.A5.getWidth()), 0, 0, 0, 0); // landscape: a real 595 x 420 page, not a rotated one
        try {
            PdfWriter writer = PdfWriter.getInstance(document, out);
            document.addTitle("Train ticket " + safe(ticket.reference()));
            document.addAuthor("Train Booking");
            document.addSubject("Train ticket");
            document.open();
            writer.setPageEmpty(false); // the page is drawn on the direct content: it is not empty
            draw(writer.getDirectContent(), document.getPageSize(), ticket, qrToken);
            document.close();
        } catch (DocumentException | IOException e) {
            throw new IllegalStateException("The ticket PDF could not be made.", e);
        }
        return out.toByteArray();
    }

    // ---- the page ------------------------------------------------------------------------------------------------------------------

    private void draw(PdfContentByte cb, Rectangle page, TicketRequest t, String qrToken) throws DocumentException, IOException {
        float x0 = MARGIN;
        float y0 = MARGIN;
        float w = page.getWidth() - 2 * MARGIN;
        float h = page.getHeight() - 2 * MARGIN;
        float x1 = x0 + w;
        float y1 = y0 + h;
        float split = x0 + w * 0.65f;

        // The card.
        cb.setColorFill(Color.WHITE);
        cb.setColorStroke(LINE);
        cb.setLineWidth(1f);
        cb.roundRectangle(x0, y0, w, h, 16);
        cb.fillStroke();

        // Header: the logo at the left, "Train ticket" at the right, a thin rule and a short red accent.
        float headerBottom = y1 - HEADER;
        drawLogo(cb, x0 + PAD, y1 - HEADER / 2);
        text(cb, bold, 12, INK_2, x1 - PAD, headerBottom + HEADER / 2 - 4, PdfContentByte.ALIGN_RIGHT, "Train ticket");
        cb.setColorStroke(LINE_SOFT);
        cb.setLineWidth(1f);
        cb.moveTo(x0 + 1, headerBottom);
        cb.lineTo(x1 - 1, headerBottom);
        cb.stroke();
        cb.setColorFill(RED);
        cb.rectangle(x0 + PAD, headerBottom - 1.5f, 36, 3);
        cb.fill();

        float footerTop = y0 + FOOTER;
        float bodyTop = headerBottom;
        drawMain(cb, t, x0 + PAD, split - 22, bodyTop, footerTop);
        drawCode(cb, t, qrToken, split, x1, bodyTop, footerTop);
        drawFooter(cb, t, x0 + PAD, x1 - PAD, footerTop, y0);

        // The perforation: a dashed line with a half-round notch at the top and the bottom edge of the card.
        cb.setColorStroke(LINE);
        cb.setLineWidth(1.2f);
        cb.setLineDash(4, 4);
        cb.moveTo(split, y1 - 10);
        cb.lineTo(split, y0 + 10);
        cb.stroke();
        cb.setLineDash(0);
        notch(cb, split, y1, 180);
        notch(cb, split, y0, 0);
        // The line between the body and the footer.
        cb.setColorStroke(LINE_SOFT);
        cb.setLineWidth(1f);
        cb.moveTo(x0 + 1, footerTop);
        cb.lineTo(x1 - 1, footerTop);
        cb.stroke();
    }

    private void notch(PdfContentByte cb, float cx, float cy, float startAngle) {
        float r = 8;
        cb.saveState();
        cb.setColorFill(Color.WHITE);
        cb.setColorStroke(LINE);
        cb.setLineWidth(1f);
        cb.arc(cx - r, cy - r, cx + r, cy + r, startAngle, 180);
        cb.closePath();
        cb.fill();
        cb.arc(cx - r, cy - r, cx + r, cy + r, startAngle, 180);
        cb.stroke();
        cb.restoreState();
    }

    private void drawLogo(PdfContentByte cb, float x, float centerY) {
        if (logo == null) {
            return;
        }
        try {
            Image image = Image.getInstance(logo);
            float height = LOGO_WIDTH * image.getHeight() / image.getWidth();
            image.scaleAbsolute(LOGO_WIDTH, height);
            image.setAbsolutePosition(x - LOGO_WIDTH * LOGO_INSET, centerY - height / 2);
            cb.addImage(image);
        } catch (IOException | DocumentException e) {
            LOG.warn("The ticket logo could not be drawn: the PDF is drawn with the plain header", e);
        }
    }

    // ---- the main area: the route and the facts --------------------------------------------------------------------------------------

    private void drawMain(PdfContentByte cb, TicketRequest t, float left, float right, float top, float bottom) {
        String dep = TIME.format(t.departure().atZoneSameInstant(zone));
        String arr = TIME.format(t.arrival().atZoneSameInstant(zone));
        float timeSize = 26;
        float timeBase = top - 22 - timeSize;
        float stationWidth = (right - left) * 0.36f;

        text(cb, bold, timeSize, INK, left, timeBase, PdfContentByte.ALIGN_LEFT, dep);
        text(cb, bold, timeSize, INK, right, timeBase, PdfContentByte.ALIGN_RIGHT, arr);
        float depWidth = bold.getWidthPoint(dep, timeSize);
        float arrWidth = bold.getWidthPoint(arr, timeSize);
        // The line with a dot at both ends, between the two times.
        float lineY = timeBase + timeSize * 0.34f;
        float lx = left + depWidth + 12;
        float rx = right - arrWidth - 12;
        if (rx - lx > 20) {
            cb.setColorStroke(LINE);
            cb.setLineWidth(2f);
            cb.moveTo(lx + 3, lineY);
            cb.lineTo(rx - 3, lineY);
            cb.stroke();
            cb.setColorFill(RED);
            cb.circle(lx + 3, lineY, 3.2f);
            cb.fill();
            cb.circle(rx - 3, lineY, 3.2f);
            cb.fill();
        }
        float stationTop = timeBase - 8;
        List<String> from = block(t.fromStation(), regular, 11, 8, stationWidth, 2);
        List<String> to = block(t.toStation(), regular, 11, 8, stationWidth, 2);
        float stationSize = Math.min(fitSize(from, regular, 11, 8, stationWidth), fitSize(to, regular, 11, 8, stationWidth));
        lines(cb, regular, stationSize, INK_2, left, stationTop, PdfContentByte.ALIGN_LEFT, from, 1.15f);
        lines(cb, regular, stationSize, INK_2, right, stationTop, PdfContentByte.ALIGN_RIGHT, to, 1.15f);

        // Facts grid, 3 columns by 2 rows, with the labels and the order of the page: Date, Train, Passenger / Class, Coach, Seats or Places.
        float gridTop = stationTop - 2 * stationSize * 1.15f - 20;
        float col = (right - left) / 3f;
        float cell = col - 10;
        String train = t.trainNumber() + (t.trainName() == null || t.trainName().isBlank() ? "" : " " + t.trainName());
        String date = DATE.format(t.departure().atZoneSameInstant(zone));
        fact(cb, "Date", date, left, gridTop, cell, 3);
        fact(cb, "Train", train, left + col, gridTop, cell, 3);
        fact(cb, "Passenger", t.passengerName(), left + 2 * col, gridTop, cell, 3);

        LinkedHashSet<String> classes = new LinkedHashSet<>();
        LinkedHashSet<String> coaches = new LinkedHashSet<>();
        for (TicketRequest.Line l : t.lines()) {
            classes.add(capitalise(l.coachClass()) + " class");
            coaches.add(l.coachCode());
        }
        float row2 = gridTop - 54;
        fact(cb, "Class", String.join(", ", classes), left, row2, cell, 3);
        fact(cb, "Coach", String.join(", ", coaches), left + col, row2, cell, 3);
        boolean anySeat = t.lines().stream().anyMatch(l -> "SEAT".equals(l.type()));
        // The seats cell takes the rest of the height: with many seats the text gets smaller, and only as a last resort is it cut with "and N more".
        float seatsBottom = bottom + 8;
        label(cb, anySeat ? "Seats" : "Places", left + 2 * col, row2);
        List<String> tokens = seatTokens(t);
        drawFitted(cb, tokens, left + 2 * col, row2 - 14, cell, row2 - 14 - seatsBottom);
    }

    private void fact(PdfContentByte cb, String label, String value, float x, float top, float width, int maxLines) {
        label(cb, label, x, top);
        List<String> v = block(value, bold, 10, 7, width, maxLines);
        lines(cb, bold, fitSize(v, bold, 10, 7, width), INK, x, top - 14, PdfContentByte.ALIGN_LEFT, v, 1.2f);
    }

    private void label(PdfContentByte cb, String label, float x, float top) {
        text(cb, regular, 7.5f, MUTED, x, top - 2, PdfContentByte.ALIGN_LEFT, label);
    }

    /** The seats of the ticket as words that can wrap: "12, 13, 14" for one coach, "Coach 3: 12, 13 · Coach 4: 7" for several, plus the standing places. */
    private List<String> seatTokens(TicketRequest t) {
        Map<String, List<String>> seatsByCoach = new LinkedHashMap<>();
        Map<String, Integer> standingByCoach = new LinkedHashMap<>();
        for (TicketRequest.Line l : t.lines()) {
            seatsByCoach.computeIfAbsent(l.coachCode(), k -> new ArrayList<>());
            if ("SEAT".equals(l.type())) {
                seatsByCoach.get(l.coachCode()).add(l.seatNumber() == null ? "?" : l.seatNumber());
            } else {
                standingByCoach.merge(l.coachCode(), l.qty(), Integer::sum);
            }
        }
        boolean oneCoach = seatsByCoach.size() == 1;
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : seatsByCoach.entrySet()) {
            List<String> seats = e.getValue();
            int standing = standingByCoach.getOrDefault(e.getKey(), 0);
            StringBuilder s = new StringBuilder();
            if (!oneCoach) {
                s.append("Coach ").append(e.getKey()).append(": ");
            }
            s.append(String.join(", ", seats));
            if (standing > 0) {
                s.append(seats.isEmpty() ? "" : " + ").append(standing).append(" standing ").append(standing == 1 ? "place" : "places");
            }
            parts.add(s.toString());
        }
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                tokens.add("·");
            }
            for (String word : parts.get(i).split(" ")) {
                tokens.add(word);
            }
        }
        return tokens;
    }

    /** Draws the words in the box with the biggest size that fits; at the smallest size it shows what fits and ends with "and N more". Never silent. */
    private void drawFitted(PdfContentByte cb, List<String> tokens, float x, float top, float width, float height) {
        float[] sizes = {10, 9, 8, 7.5f, 7, 6.5f};
        for (float size : sizes) {
            List<String> lines = wrapTokens(tokens, bold, size, width);
            if (lines.size() * size * 1.2f <= height) {
                lines(cb, bold, size, INK, x, top, PdfContentByte.ALIGN_LEFT, lines, 1.2f);
                return;
            }
        }
        float size = sizes[sizes.length - 1];
        int keep = tokens.size();
        while (keep > 1) {
            keep--;
            int dropped = countSeatWords(tokens.subList(keep, tokens.size()));
            List<String> shown = new ArrayList<>(tokens.subList(0, keep));
            shown.add("and " + dropped + " more");
            List<String> lines = wrapTokens(shown, bold, size, width);
            if (lines.size() * size * 1.2f <= height) {
                lines(cb, bold, size, INK, x, top, PdfContentByte.ALIGN_LEFT, lines, 1.2f);
                return;
            }
        }
        lines(cb, bold, size, INK, x, top, PdfContentByte.ALIGN_LEFT, wrapTokens(List.of("and " + tokens.size() + " more"), bold, size, width), 1.2f);
    }

    private static int countSeatWords(List<String> words) {
        return (int) words.stream().filter(w -> !w.equals("·")).count();
    }

    // ---- the code panel -------------------------------------------------------------------------------------------------------------

    private void drawCode(PdfContentByte cb, TicketRequest t, String qrToken, float left, float right, float top, float bottom) throws DocumentException, IOException {
        float cx = (left + right) / 2;
        float qrTop = top - 22;
        Image qr = Image.getInstance(QrImages.png(qrToken, QrImages.PRINT_SIZE));
        // Dark on white, drawn square and unsmoothed by the viewer for a whole number of printed pixels per module is not needed: the image is far bigger than the print.
        qr.scaleAbsolute(QR_SIZE, QR_SIZE);
        qr.setAbsolutePosition(cx - QR_SIZE / 2, qrTop - QR_SIZE);
        cb.addImage(qr);
        float y = qrTop - QR_SIZE - 12;
        text(cb, regular, 7.5f, MUTED, cx, y, PdfContentByte.ALIGN_CENTER, "Show this code at the gate");
        y -= 22;
        text(cb, regular, 7.5f, MUTED, cx, y, PdfContentByte.ALIGN_CENTER, "Booking reference");
        String ref = safe(t.reference());
        float size = 15;
        while (size > 8 && mono.getWidthPoint(ref, size) > right - left - 24) {
            size -= 0.5f;
        }
        text(cb, mono, size, INK, cx, y - 17, PdfContentByte.ALIGN_CENTER, ref);
    }

    // ---- the footer -----------------------------------------------------------------------------------------------------------------

    private void drawFooter(PdfContentByte cb, TicketRequest t, float left, float right, float top, float bottom) {
        String fare = t.amount().toPlainString() + " " + t.currency();
        // The payment line says how it was paid: by card, at the counter, or not at all (the fare value is still shown).
        String payment = switch (t.method()) {
            case "COUNTER" -> "Paid at counter  " + fare;
            case "COMPLIMENTARY" -> "Complimentary  (no charge, fare value " + fare + ")";
            default -> "Total paid  " + fare;
        };
        text(cb, bold, 11, INK, left, top - 18, PdfContentByte.ALIGN_LEFT, payment);
        List<String> parts = new ArrayList<>();
        for (TicketRequest.Line l : t.lines()) {
            String what = "SEAT".equals(l.type()) ? "Seat " + l.seatNumber() : l.qty() + " standing " + (l.qty() == 1 ? "place" : "places") + " (" + l.coachCode() + ")";
            parts.add(what + " " + l.price().toPlainString() + " " + t.currency());
        }
        List<String> breakdown = wrapTokens(joinWith(parts), regular, 7, right - left);
        if (breakdown.size() <= 2) { // only when there is room
            lines(cb, regular, 7, MUTED, left, top - 29, PdfContentByte.ALIGN_LEFT, breakdown, 1.2f);
        }
        text(cb, regular, 7, MUTED, left, bottom + 9, PdfContentByte.ALIGN_LEFT,
                "Valid while the booking is confirmed. If the booking is cancelled, the code stops working.");
    }

    private static List<String> joinWith(List<String> parts) {
        List<String> words = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                words.add("·");
            }
            for (String w : parts.get(i).split(" ")) {
                words.add(w);
            }
        }
        return words;
    }

    // ---- text helpers ---------------------------------------------------------------------------------------------------------------

    private void text(PdfContentByte cb, BaseFont font, float size, Color color, float x, float y, int align, String value) {
        cb.beginText();
        cb.setFontAndSize(font, size);
        cb.setColorFill(color);
        cb.showTextAligned(align, safe(value), x, y, 0);
        cb.endText();
    }

    /** Lines of text, the first one with its top at {@code top}. */
    private void lines(PdfContentByte cb, BaseFont font, float size, Color color, float x, float top, int align, List<String> lines, float leading) {
        float y = top - size;
        for (String line : lines) {
            text(cb, font, size, color, x, y, align, line);
            y -= size * leading;
        }
    }

    /** One text in a box of the given width: on one line at the biggest size down to {@code min}, otherwise wrapped over up to {@code maxLines} lines. */
    private List<String> block(String value, BaseFont font, float max, float min, float width, int maxLines) {
        String v = safe(value);
        if (font.getWidthPoint(v, max) <= width) {
            return List.of(v);
        }
        for (float size = max; size >= min; size -= 0.5f) { // first try to keep it on one line by making it smaller
            if (font.getWidthPoint(v, size) <= width) {
                return List.of(v);
            }
        }
        for (float size = max; size >= min; size -= 0.5f) {
            List<String> lines = wrap(v, font, size, width);
            if (lines.size() <= maxLines) {
                return lines;
            }
        }
        List<String> lines = wrap(v, font, min, width);
        List<String> cut = new ArrayList<>(lines.subList(0, maxLines));
        String last = cut.get(maxLines - 1);
        while (last.length() > 1 && font.getWidthPoint(last + "...", min) > width) {
            last = last.substring(0, last.length() - 1);
        }
        cut.set(maxLines - 1, last + "...");
        return cut;
    }

    /** The biggest size (from max down to min) at which the lines fit the width; the lines were made for that range. */
    private float fitSize(List<String> lines, BaseFont font, float max, float min, float width) {
        for (float size = max; size > min; size -= 0.5f) {
            boolean fits = true;
            for (String line : lines) {
                if (font.getWidthPoint(line, size) > width) {
                    fits = false;
                    break;
                }
            }
            if (fits) {
                return size;
            }
        }
        return min;
    }

    private List<String> wrap(String value, BaseFont font, float size, float width) {
        return wrapTokens(List.of(value.split(" ")), font, size, width);
    }

    /** Wraps words to the width; a word longer than the width is broken by characters. */
    private List<String> wrapTokens(List<String> words, BaseFont font, float size, float width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String raw : words) {
            String word = safe(raw);
            while (font.getWidthPoint(word, size) > width && word.length() > 1) {
                int n = word.length();
                while (n > 1 && font.getWidthPoint(word.substring(0, n), size) > width) {
                    n--;
                }
                if (line.length() > 0) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                lines.add(word.substring(0, n));
                word = word.substring(n);
            }
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (font.getWidthPoint(candidate, size) <= width) {
                line.setLength(0);
                line.append(candidate);
            } else {
                if (line.length() > 0) {
                    lines.add(line.toString());
                }
                line.setLength(0);
                line.append(word);
            }
        }
        if (line.length() > 0) {
            lines.add(line.toString());
        }
        return lines;
    }

    private static String capitalise(String value) {
        return value == null || value.isEmpty() ? "" : value.substring(0, 1).toUpperCase(Locale.ENGLISH) + value.substring(1).toLowerCase(Locale.ENGLISH);
    }

    /**
     * Makes a text safe for the WinAnsi fonts: a character the encoding has stays; an accented letter outside it loses its accent; anything else
     * becomes a question mark. It never throws and never changes the length of the information more than that.
     */
    static String safe(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        value.codePoints().forEach(cp -> {
            if (cp < 32 || cp == 127) {
                out.append(' ');
                return;
            }
            String s = new String(Character.toChars(cp));
            synchronized (WIN_ANSI) {
                if (cp <= 0xFFFF && WIN_ANSI.canEncode((char) cp)) {
                    out.append(s);
                    return;
                }
            }
            String stripped = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
            boolean ok = !stripped.isEmpty() && stripped.chars().allMatch(c -> {
                synchronized (WIN_ANSI) {
                    return WIN_ANSI.canEncode((char) c);
                }
            });
            out.append(ok ? stripped : "?");
        });
        return out.toString();
    }
}
