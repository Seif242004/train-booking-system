package com.trainbooking.ticket.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import com.lowagie.text.pdf.PdfName;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import com.trainbooking.ticket.testsupport.DatabaseTest;
import com.trainbooking.ticket.ticket.service.TicketPdfRenderer;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/** What the PDF shows (SRS FR-T2). Needs no database. */
class TicketPdfRendererTest {

    // The renderer has no state and no database: a plain object in the Cairo zone.
    private final TicketPdfRenderer renderer = new TicketPdfRenderer(ZoneId.of("Africa/Cairo"));

    // Reads the text of page 1 back from the PDF bytes and flattens the white space.
    private String text(byte[] pdf) throws Exception {
        PdfReader reader = new PdfReader(pdf);
        try {
            return new PdfTextExtractor(reader).getTextFromPage(1).replaceAll("\\s+", " "); // line breaks and runs of spaces do not matter
        } finally {
            reader.close();
        }
    }

    // {@code @Test} marks a JUnit 5 test method.
    @Test
    // The extracted text holds the reference, the passenger, the train, the stations, the seat and the standing places, the prices and
    // the total.
    void theTicket_showsEverythingThePassengerNeeds() throws Exception {
        byte[] pdf = renderer.render(DatabaseTest.ticketRequest("TB-ABC234"), "TB-ABC234.nonce.signature");

        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        String text = text(pdf);
        assertThat(text).contains("TB-ABC234", "Alice Example", "T100 Express", "Cairo", "Alexandria");
        assertThat(text).contains("1A", "A1", "2 standing places", "B2", "First class", "Second class", "Seat 1A 230.00 EGP");
        assertThat(text).contains("230.00 EGP", "300.00 EGP", "Total paid 530.00 EGP");
    }

    @Test
    // Converts the instants to Africa/Cairo: 06:00 UTC prints as 08:00 and 08:45 UTC as 10:45, on Monday 2 November 2026.
    void theTimes_areShownInTheTimeZoneOfTheSystem_notInUtc() throws Exception {
        // 06:00 UTC on 2 November 2026 is 08:00 in Cairo (UTC+2), and 08:45 UTC is 10:45.
        String text = text(renderer.render(DatabaseTest.ticketRequest("TB-ABC234"), "TB-ABC234.nonce.signature"));

        assertThat(text).contains("Monday 2 November 2026", "08:00", "10:45", "Cairo", "Alexandria");
    }

    @Test
    // The page has two image objects: the QR code and the logo (the default renderer loads the logo from the classpath).
    void theQrCode_isOnThePage() throws Exception {
        PdfReader reader = new PdfReader(renderer.render(DatabaseTest.ticketRequest("TB-ABC234"), "TB-ABC234.nonce.signature"));
        try {
            var resources = reader.getPageN(1).getAsDict(PdfName.RESOURCES);
            assertThat(resources.getAsDict(PdfName.XOBJECT)).isNotNull();
            assertThat(resources.getAsDict(PdfName.XOBJECT).size()).isEqualTo(2); // the QR code and the red logo
        } finally {
            reader.close();
        }
    }

    // Makes a small PNG in memory, used as a fake logo.
    private static byte[] png(int width, int height) throws Exception {
        var image = new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(java.awt.Color.RED);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        var out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "PNG", out);
        return out.toByteArray();
    }

    // Counts the images drawn on page 1 (the QR, plus the logo when there is one).
    private static int images(byte[] pdf) throws Exception {
        PdfReader reader = new PdfReader(pdf);
        try {
            return reader.getPageN(1).getAsDict(PdfName.RESOURCES).getAsDict(PdfName.XOBJECT).size();
        } finally {
            reader.close();
        }
    }

    @Test
    // A generated 1200 x 336 PNG as logo: the page has two images (logo and QR) and still shows the reference and the total.
    void withALogo_itIsDrawnAtTheTop_besidesTheQrCode() throws Exception {
        byte[] pdf = new TicketPdfRenderer(ZoneId.of("Africa/Cairo"), png(1200, 336)).render(DatabaseTest.ticketRequest("TB-ABC234"), "TB-ABC234.nonce.signature");

        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        assertThat(images(pdf)).isEqualTo(2); // the logo and the QR code
        assertThat(text(pdf)).contains("TB-ABC234", "Total paid 530.00 EGP");
    }

    @Test
    // Bytes that are not an image: the logo is skipped (one image, the QR) and the ticket is still made.
    void aLogoThatIsNotAnImage_isSkipped_theTicketIsStillMade() throws Exception {
        byte[] pdf = new TicketPdfRenderer(ZoneId.of("Africa/Cairo"), "not an image".getBytes(StandardCharsets.UTF_8))
                .render(DatabaseTest.ticketRequest("TB-ABC234"), "TB-ABC234.nonce.signature");

        assertThat(images(pdf)).isEqualTo(1);
        assertThat(text(pdf)).contains("TB-ABC234");
    }

    @Test
    // loadLogo returns null for an unknown resource; the official logo resource loads; the default renderer works and draws two images.
    void aMissingLogoResource_givesNull_andTheDefaultRendererStillWorks() throws Exception {
        assertThat(TicketPdfRenderer.loadLogo("brand/does-not-exist.png")).isNull();
        // The resource the service looks for: when the official PNG is added it must load; until then the header is plain.
        byte[] pdf = new TicketPdfRenderer(ZoneId.of("Africa/Cairo")).render(DatabaseTest.ticketRequest("TB-ABC234"), "TB-ABC234.nonce.signature");
        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        assertThat(pdf.length).isPositive();
        // The official red logo is on the classpath now: the default renderer draws it besides the QR code.
        assertThat(TicketPdfRenderer.loadLogo(TicketPdfRenderer.LOGO_RESOURCE)).isNotNull();
        assertThat(images(pdf)).isEqualTo(2);
    }

    @Test
    // The footer text follows the payment method: card, counter, complimentary; the last two never say "Total paid".
    void thePaymentLine_saysHowItWasPaid() throws Exception {
        var base = DatabaseTest.ticketRequest("TB-ABC234");
        for (String[] c : new String[][] {{"COUNTER", "Paid at counter 530.00 EGP"}, {"COMPLIMENTARY", "Complimentary (no charge, fare value 530.00 EGP)"}, {"CARD", "Total paid 530.00 EGP"}}) {
            var request = new com.trainbooking.ticket.ticket.model.TicketRequest(base.reference(), base.passengerName(), base.trainNumber(), base.trainName(), base.fromStation(),
                    base.departure(), base.toStation(), base.arrival(), base.lines(), base.amount(), base.currency(), c[0]);
            String text = text(renderer.render(request, "TB-ABC234.nonce.signature"));
            assertThat(text).contains(c[1]);
            if (!c[0].equals("CARD")) assertThat(text).doesNotContain("Total paid");
        }
    }

    // ---- the redesigned page (one A5 landscape card) ------------------------------------------------------------------------------

    private static final java.time.OffsetDateTime DEPARTURE = java.time.OffsetDateTime.parse("2026-11-02T06:00:00Z");
    private static final java.time.OffsetDateTime ARRIVAL = java.time.OffsetDateTime.parse("2026-11-02T08:45:00Z");

    // A builder of requests for the layout tests: the total is the sum of the line prices.
    private static com.trainbooking.ticket.ticket.model.TicketRequest request(String passenger, String from, String to, String method, java.util.List<com.trainbooking.ticket.ticket.model.TicketRequest.Line> lines) {
        java.math.BigDecimal total = lines.stream().map(l -> l.price()).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
        return new com.trainbooking.ticket.ticket.model.TicketRequest("TB-ABC234", passenger, "T100", "Express", from, DEPARTURE, to, ARRIVAL, lines, total, "EGP", method);
    }

    // One seat line at a fixed price of 115.00 EGP.
    private static com.trainbooking.ticket.ticket.model.TicketRequest.Line seat(String coach, String clazz, String number) {
        return new com.trainbooking.ticket.ticket.model.TicketRequest.Line("SEAT", coach, clazz, number, 1, new java.math.BigDecimal("115.00"));
    }

    // Page count of the PDF.
    private static int pages(byte[] pdf) throws Exception {
        PdfReader reader = new PdfReader(pdf);
        try {
            return reader.getNumberOfPages();
        } finally {
            reader.close();
        }
    }

    @Test
    // One page of 595 x 420 points and the title and subject in the metadata.
    void theTicket_isExactlyOneLandscapeA5Page_withTheMetadata() throws Exception {
        byte[] pdf = renderer.render(DatabaseTest.ticketRequest("TB-ABC234"), "TB-ABC234.nonce.signature");

        PdfReader reader = new PdfReader(pdf);
        try {
            assertThat(reader.getNumberOfPages()).isEqualTo(1);
            assertThat(reader.getPageSize(1).getWidth()).isGreaterThan(reader.getPageSize(1).getHeight()); // landscape
            assertThat(reader.getPageSize(1).getWidth()).isBetween(594f, 597f);
            assertThat(reader.getInfo().get("Title")).isEqualTo("Train ticket TB-ABC234");
        } finally {
            reader.close();
        }
        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }

    @Test
    // For card, counter and complimentary payments, every fact of the page appears in the text.
    void everyFactOfThePageTicket_isInTheText_forEachPaymentMethod() throws Exception {
        for (String[] c : new String[][] {{"CARD", "Total paid 345.00 EGP"}, {"COUNTER", "Paid at counter 345.00 EGP"}, {"COMPLIMENTARY", "Complimentary (no charge, fare value 345.00 EGP)"}}) {
            var r = request("Alice Example", "Cairo", "Alexandria", c[0], java.util.List.of(seat("A1", "FIRST", "12"), seat("A1", "FIRST", "13"), seat("A1", "FIRST", "14")));
            byte[] pdf = renderer.render(r, "TB-ABC234.nonce.signature");
            assertThat(pages(pdf)).isEqualTo(1);
            String text = text(pdf);
            assertThat(text).contains("TB-ABC234", "Alice Example", "Cairo", "Alexandria", "08:00", "10:45", "Monday 2 November 2026", "T100 Express", "First class", "A1",
                    "12, 13, 14", c[1], "Booking reference", "Show this code at the gate", "Valid while the booking is confirmed", "Train ticket");
        }
    }

    @Test
    // 20 seats over two coaches (10 and 10) still fit on one page and every seat number is listed, with no "and N more".
    void theMaximumSeatsAcrossTwoCoaches_stayOnOnePage_andAreAllListed() throws Exception {
        java.util.List<com.trainbooking.ticket.ticket.model.TicketRequest.Line> lines = new java.util.ArrayList<>();
        for (int i = 1; i <= 10; i++) lines.add(seat("A1", "FIRST", i + "A"));
        for (int i = 1; i <= 10; i++) lines.add(seat("B2", "SECOND", i + "B"));
        byte[] pdf = renderer.render(request("Alice Example", "Cairo", "Alexandria", "CARD", lines), "TB-ABC234.nonce.signature");

        assertThat(pages(pdf)).isEqualTo(1);
        String text = text(pdf);
        for (int i = 1; i <= 10; i++) assertThat(text).contains(i + "A").contains(i + "B");
        assertThat(text).contains("Coach A1", "Coach B2").doesNotContain("more");
    }

    @Test
    // Seats and standing places of one coach are listed together.
    void seatsPlusStandingPlaces_areListedTogether() throws Exception {
        var lines = java.util.List.of(seat("A1", "FIRST", "12"), seat("A1", "FIRST", "13"),
                new com.trainbooking.ticket.ticket.model.TicketRequest.Line("STANDING", "A1", "FIRST", null, 2, new java.math.BigDecimal("200.00")));
        byte[] pdf = renderer.render(request("Alice Example", "Cairo", "Alexandria", "CARD", lines), "TB-ABC234.nonce.signature");

        assertThat(pages(pdf)).isEqualTo(1);
        assertThat(text(pdf)).contains("12, 13 + 2 standing places");
    }

    @Test
    // Very long names and stations shrink or wrap or end with "..."; nothing throws and there is still one page.
    void longNames_doNotThrow_andStayOnOnePage() throws Exception {
        String name = "Abdelrahman Mohamed Abdelfattah Ibrahim El-Sayed El-Shennawy Junior";
        String station = "Alexandria (Sidi Gaber) International Terminal and Central Interchange Station";
        var r = new com.trainbooking.ticket.ticket.model.TicketRequest("TB-ABC234", name.substring(0, 60), "T100", "Express Night Service Alexandria To Aswan Via Everything",
                station, DEPARTURE, station, ARRIVAL, java.util.List.of(seat("A1", "FIRST", "1A")), new java.math.BigDecimal("115.00"), "EGP", "CARD");
        byte[] pdf = renderer.render(r, "TB-ABC234.nonce.signature");

        assertThat(pages(pdf)).isEqualTo(1);
        assertThat(text(pdf)).contains("Abdelrahman", "Alexandria", "1A");
    }

    @Test
    // Names with characters the fonts cannot show (accents, Arabic, emoji) are made safe and never break the rendering.
    void charactersOutsideWinAnsi_neverBreakTheRendering() throws Exception {
        for (String name : new String[] {"Zoë Müller", "\u0645\u062D\u0645\u062F \u0639\u0644\u064A", "\u5F20\u4F1F \uD83D\uDE80 Test", "Łukasz Żółć", "tab\tand\nnewline"}) {
            byte[] pdf = renderer.render(request(name, name, name, "CARD", java.util.List.of(seat("A1", "FIRST", "1A"))), "TB-ABC234.nonce.signature");
            assertThat(pages(pdf)).isEqualTo(1);
        }
        assertThat(text(renderer.render(request("Zo\u00EB M\u00FCller", "Cairo", "Alexandria", "CARD", java.util.List.of(seat("A1", "FIRST", "1A"))), "t.n.s"))).contains("Zo\u00EB M\u00FCller");
    }

    @Test
    // The default renderer draws two images (the logo from the classpath and the QR); a renderer made with a null logo draws only the QR.
    void theLogoShipsOnTheClasspath_andIsDrawnByTheDefaultRenderer_butNotWhenDisabled() throws Exception {
        var r = DatabaseTest.ticketRequest("TB-ABC234");
        assertThat(images(new TicketPdfRenderer(ZoneId.of("Africa/Cairo")).render(r, "TB-ABC234.nonce.signature"))).isEqualTo(2); // the logo and the QR code
        assertThat(images(new TicketPdfRenderer(ZoneId.of("Africa/Cairo"), null).render(r, "TB-ABC234.nonce.signature"))).isEqualTo(1); // the QR code only
    }

    /**
     * Writes two sample tickets to .local/sample-tickets/ (git-ignored) so they can be looked at: run this class' test, no assertion on
     * the look.
     */
    @Test
    // Not a check of the look: it writes two sample PDFs to the git-ignored .local/sample-tickets folder so a person can open them, and
    // only
    // asserts that the first file is not empty.
    void writeTheSampleTickets() throws Exception {
        java.nio.file.Path dir = java.nio.file.Path.of(System.getProperty("user.dir")).resolve("../../.local/sample-tickets").normalize();
        java.nio.file.Files.createDirectories(dir);
        java.nio.file.Files.write(dir.resolve("sample-card-ticket.pdf"), renderer.render(DatabaseTest.ticketRequest("TB-SAMPLE1"), "TB-SAMPLE1.nonce.signature"));
        var lines = new java.util.ArrayList<com.trainbooking.ticket.ticket.model.TicketRequest.Line>();
        for (int i = 1; i <= 8; i++) lines.add(seat("A1", "FIRST", i + "A"));
        for (int i = 1; i <= 6; i++) lines.add(seat("B2", "SECOND", i + "C"));
        lines.add(new com.trainbooking.ticket.ticket.model.TicketRequest.Line("STANDING", "B2", "SECOND", null, 3, new java.math.BigDecimal("120.00")));
        var long1 = new com.trainbooking.ticket.ticket.model.TicketRequest("TB-SAMPLE2", "Abdelrahman Mohamed Abdelfattah El-Sayed El-Shennawy", "1005", "Night 1005 Aswan via Giza, Wasta, Minya, Assiut",
                "Alexandria (Sidi Gaber) Central Interchange", DEPARTURE, "Kom Ombo Temple Station", ARRIVAL, lines, lines.stream().map(l -> l.price()).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add), "EGP", "COUNTER");
        java.nio.file.Files.write(dir.resolve("sample-counter-ticket-long-names.pdf"), renderer.render(long1, "TB-SAMPLE2.nonce.signature"));
        assertThat(java.nio.file.Files.size(dir.resolve("sample-card-ticket.pdf"))).isPositive();
    }
}
