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
            assertThat(resources.getAsDict(PdfName.XOBJECT).size()).isEqualTo(1);
        } finally {
            reader.close();
        }
    }
}
