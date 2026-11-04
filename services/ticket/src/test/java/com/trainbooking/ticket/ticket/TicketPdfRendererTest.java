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

    private final TicketPdfRenderer renderer = new TicketPdfRenderer(ZoneId.of("Africa/Cairo"));

    private String text(byte[] pdf) throws Exception {
        PdfReader reader = new PdfReader(pdf);
        try {
            return new PdfTextExtractor(reader).getTextFromPage(1);
        } finally {
            reader.close();
        }
    }

    @Test
    void theTicket_showsEverythingThePassengerNeeds() throws Exception {
        byte[] pdf = renderer.render(DatabaseTest.ticketRequest("TB-ABC234"), "TB-ABC234.nonce.signature");

        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        String text = text(pdf);
        assertThat(text).contains("TB-ABC234", "Alice Example", "T100 Express", "Cairo", "Alexandria");
        assertThat(text).contains("Seat 1A", "A1 (first)", "2 standing places", "B2 (second)");
        assertThat(text).contains("230.00 EGP", "300.00 EGP", "Total paid  530.00 EGP");
    }

    @Test
    void theTimes_areShownInTheTimeZoneOfTheSystem_notInUtc() throws Exception {
        // 06:00 UTC on 2 November 2026 is 08:00 in Cairo (UTC+2), and 08:45 UTC is 10:45.
        String text = text(renderer.render(DatabaseTest.ticketRequest("TB-ABC234"), "TB-ABC234.nonce.signature"));

        assertThat(text).contains("Monday 2 November 2026", "Cairo  08:00", "Alexandria  10:45");
    }

    @Test
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

    private static int images(byte[] pdf) throws Exception {
        PdfReader reader = new PdfReader(pdf);
        try {
            return reader.getPageN(1).getAsDict(PdfName.RESOURCES).getAsDict(PdfName.XOBJECT).size();
        } finally {
            reader.close();
        }
    }

    @Test
    void withALogo_itIsDrawnAtTheTop_besidesTheQrCode() throws Exception {
        byte[] pdf = new TicketPdfRenderer(ZoneId.of("Africa/Cairo"), png(1200, 336)).render(DatabaseTest.ticketRequest("TB-ABC234"), "TB-ABC234.nonce.signature");

        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        assertThat(images(pdf)).isEqualTo(2); // the logo and the QR code
        assertThat(text(pdf)).contains("TB-ABC234", "Total paid  530.00 EGP");
    }

    @Test
    void aLogoThatIsNotAnImage_isSkipped_theTicketIsStillMade() throws Exception {
        byte[] pdf = new TicketPdfRenderer(ZoneId.of("Africa/Cairo"), "not an image".getBytes(StandardCharsets.UTF_8))
                .render(DatabaseTest.ticketRequest("TB-ABC234"), "TB-ABC234.nonce.signature");

        assertThat(images(pdf)).isEqualTo(1);
        assertThat(text(pdf)).contains("TB-ABC234");
    }

    @Test
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
}
