package com.trainbooking.ticket.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trainbooking.ticket.testsupport.DatabaseTest;
import com.trainbooking.ticket.ticket.model.Ticket;
import com.trainbooking.ticket.ticket.model.TicketNotFoundException;
import com.trainbooking.ticket.ticket.model.TicketStatus;
import com.trainbooking.ticket.ticket.repository.TicketRepository;
import com.trainbooking.ticket.ticket.service.QrTokens;
import com.trainbooking.ticket.ticket.service.TicketService;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** One PDF ticket per booking: made once, kept, invalidated when the booking is cancelled. Real Oracle, no mocks. */
class TicketServiceDatabaseTest extends DatabaseTest {

    // {@code @Autowired}: Spring injects the bean into the field.
    @Autowired
    TicketService service;
    @Autowired
    TicketRepository repository;

    // {@code @Test} marks a JUnit 5 test method.
    @Test
    // A new ticket is VALID, has a PDF (starts with %PDF-, over 1000 bytes), its QR token verifies with the key of this service, and one
    // CREATE audit row exists.
    void issue_makesAValidTicket_withAPdf_andAQrTokenOnlyThisServiceCouldSign() {
        TicketService.Issued issued = service.issue(7, ticketRequest("TB-ABC234"));

        assertThat(issued.created()).isTrue();
        Ticket ticket = issued.ticket();
        assertThat(ticket.status()).isEqualTo(TicketStatus.VALID);
        assertThat(ticket.reference()).isEqualTo("TB-ABC234");
        assertThat(new QrTokens(SIGNING_KEY).verify(ticket.qrToken())).contains("TB-ABC234");
        byte[] pdf = service.pdf(7);
        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        assertThat(pdf.length).isGreaterThan(1000);
        assertThat(rows("audit_log WHERE entity = 'ticket' AND action = 'CREATE'")).isEqualTo(1);
    }

    @Test
    // Idempotence: a second request returns the first ticket (created = false) with the same id, token and PDF bytes, and stores nothing
    // new.
    void issue_again_returnsTheSameTicketAndQr_andStoresNothingNew() {
        Ticket first = service.issue(7, ticketRequest("TB-ABC234")).ticket();
        byte[] pdf = service.pdf(7);

        TicketService.Issued again = service.issue(7, ticketRequest("TB-ABC234"));

        assertThat(again.created()).isFalse();
        assertThat(again.ticket().id()).isEqualTo(first.id());
        assertThat(again.ticket().qrToken()).isEqualTo(first.qrToken());
        assertThat(service.pdf(7)).isEqualTo(pdf);
        assertThat(rows("ticket")).isEqualTo(1);
    }

    @Test
    // Concurrency: two threads start at the same moment (a latch holds them back), both call issue for the same booking. The unique key
    // lets
    // one INSERT win; the other gets the duplicate-key error and answers with the winner's ticket. One row, one token for both.
    void issue_twoAtTheSameMoment_makeOneTicket() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<TicketService.Issued>> results = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            results.add(pool.submit(() -> {
                go.await();
                return service.issue(7, ticketRequest("TB-ABC234"));
            }));
        }
        go.countDown();
        List<String> tokens = new ArrayList<>();
        for (Future<TicketService.Issued> result : results) {
            tokens.add(result.get().ticket().qrToken());
        }
        pool.shutdown();

        assertThat(rows("ticket")).isEqualTo(1);
        assertThat(tokens.get(0)).isEqualTo(tokens.get(1)); // both callers got the one ticket
    }

    @Test
    // Tickets are per booking: a second booking gets its own row and its own token.
    void anotherBooking_getsItsOwnTicket() {
        Ticket a = service.issue(7, ticketRequest("TB-AAA222")).ticket();
        Ticket b = service.issue(8, ticketRequest("TB-BBB333")).ticket();

        assertThat(a.qrToken()).isNotEqualTo(b.qrToken());
        assertThat(rows("ticket")).isEqualTo(2);
    }

    @Test
    // The first call sets the status and the time (the test clock moves an hour between calls); the second keeps the first time; the PDF is
    // still there as the record; only one INVALIDATE audit row exists.
    void invalidate_turnsTheTicketOff_once_andKeepsThePdfAsTheRecord() {
        service.issue(7, ticketRequest("TB-ABC234"));
        clock.advance(Duration.ofHours(1));

        Ticket invalidated = service.invalidate(7);
        OffsetDateTime when = invalidated.invalidatedAt();
        clock.advance(Duration.ofHours(1));
        Ticket again = service.invalidate(7);

        assertThat(invalidated.status()).isEqualTo(TicketStatus.INVALIDATED);
        assertThat(when).isNotNull();
        assertThat(again.invalidatedAt()).isEqualTo(when); // a repeat changes nothing
        assertThat(service.pdf(7).length).isGreaterThan(1000);
        assertThat(rows("audit_log WHERE action = 'INVALIDATE'")).isEqualTo(1);
    }

    @Test
    // No resurrection: the same request after an invalidation returns the INVALIDATED ticket.
    void issue_afterAnInvalidation_neverBringsTheTicketBack() {
        service.issue(7, ticketRequest("TB-ABC234"));
        service.invalidate(7);

        TicketService.Issued again = service.issue(7, ticketRequest("TB-ABC234"));

        assertThat(again.created()).isFalse();
        assertThat(again.ticket().status()).isEqualTo(TicketStatus.INVALIDATED);
    }

    @Test
    // get, pdf and invalidate of an unknown booking throw TicketNotFoundException.
    void aBookingWithoutATicket_isNotFound() {
        assertThatThrownBy(() -> service.get(99)).isInstanceOf(TicketNotFoundException.class);
        assertThatThrownBy(() -> service.pdf(99)).isInstanceOf(TicketNotFoundException.class);
        assertThatThrownBy(() -> service.invalidate(99)).isInstanceOf(TicketNotFoundException.class);
    }

    @Test
    // 300,000 random bytes are written through the repository and read back byte for byte: a BLOB can hold a PDF larger than the plain
    // limits.
    void aLargePdf_survivesTheBlobColumn() {
        byte[] big = new byte[300_000];
        new java.util.Random(42).nextBytes(big);

        repository.insert(5, "TB-BIG111", "TB-BIG111.n.s", big, OffsetDateTime.parse("2026-11-01T10:00:00Z"));

        assertThat(repository.findPdf(5)).hasValueSatisfying(stored -> assertThat(stored).isEqualTo(big));
    }
}
