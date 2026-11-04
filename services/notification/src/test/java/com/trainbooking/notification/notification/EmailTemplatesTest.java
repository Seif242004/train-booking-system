package com.trainbooking.notification.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.trainbooking.notification.notification.model.NotificationRequest;
import com.trainbooking.notification.notification.service.EmailTemplates;
import com.trainbooking.notification.testsupport.DatabaseTest;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The words of the emails. Needs no database or mail server. */
class EmailTemplatesTest {

    private final EmailTemplates templates = new EmailTemplates(ZoneId.of("Africa/Cairo"));

    private static NotificationRequest of(NotificationRequest.Kind kind, BigDecimal amount, String detail, String name) {
        NotificationRequest base = DatabaseTest.ticketEmail("k");
        return new NotificationRequest("k", kind, base.to(), name, base.reference(), base.trip(), base.lines(), amount, "EGP", detail, null);
    }

    @Test
    void theTicketEmail_saysWhatWasBooked_withTimesInTheLocalTimeZone() {
        var email = templates.render(DatabaseTest.ticketEmail("k"));

        assertThat(email.subject()).isEqualTo("Your train ticket TB-ABC234");
        assertThat(email.text()).contains("Hello Alice Example,", "TB-ABC234", "attached", "Cairo 08:00 to Alexandria 10:45", "Monday 2 November 2026",
                "T100 Express", "- Seat 1A, coach A1 (first): 230.00 EGP", "Total paid: 230.00 EGP");
        assertThat(email.html()).contains("<p>Hello Alice Example,</p>", "<li>Seat 1A, coach A1 (first): 230.00 EGP</li>");
    }

    @Test
    void theCancellationEmail_promisesTheFullRefund_whenThereIsOne() {
        var email = templates.render(of(NotificationRequest.Kind.CANCELLATION, new BigDecimal("230.00"), "Cancelled by support at your request.", "Alice"));

        assertThat(email.subject()).isEqualTo("Your booking TB-ABC234 is cancelled");
        assertThat(email.text()).contains("has been cancelled", "230.00 EGP, is being refunded", "Cancelled by support at your request.");
        assertThat(email.text()).doesNotContain("Total paid");
    }

    @Test
    void theRefundEmail_saysTheMoneyIsBack() {
        var email = templates.render(of(NotificationRequest.Kind.REFUND, new BigDecimal("230.00"), null, null));

        assertThat(email.subject()).isEqualTo("Your refund for booking TB-ABC234");
        assertThat(email.text()).startsWith("Hello,\n").contains("The refund of 230.00 EGP for booking TB-ABC234 is done");
    }

    @Test
    void whatCameFromTheRequest_isEscapedInTheHtml() {
        var email = templates.render(of(NotificationRequest.Kind.CANCELLATION, null, "<script>alert('x')</script>", "<b>Mallory</b>"));

        assertThat(email.html()).doesNotContain("<script>").doesNotContain("<b>Mallory</b>");
        assertThat(email.html()).contains("&lt;script&gt;").contains("&lt;b&gt;Mallory&lt;/b&gt;");
        assertThat(email.text()).contains("<script>"); // plain text needs no escaping
    }

    @Test
    void aRequestWithoutATripOrLines_stillMakesAnEmail() {
        var request = new NotificationRequest("k", NotificationRequest.Kind.REFUND, "a@b.co", null, "TB-ABC234", null, List.of(), null, null, null, null);

        assertThat(templates.render(request).text()).contains("your payment", "TB-ABC234");
    }

    private static NotificationRequest by(NotificationRequest.Kind kind, String method) {
        NotificationRequest base = DatabaseTest.ticketEmail("k");
        return new NotificationRequest("k", kind, base.to(), base.toName(), base.reference(), base.trip(), base.lines(), new BigDecimal("230.00"), "EGP", null, null, method);
    }

    @Test
    void aCounterBooking_isNeverPromisedACardRefund_andTheTicketSaysPaidAtCounter() {
        assertThat(templates.render(by(NotificationRequest.Kind.CANCELLATION, "COUNTER")).text())
                .contains("paid 230.00 EGP at the counter", "handled at the support desk").doesNotContain("your card");
        assertThat(templates.render(by(NotificationRequest.Kind.TICKET, "COUNTER")).text()).contains("Paid at counter: 230.00 EGP").doesNotContain("Total paid");
    }

    @Test
    void aComplimentaryBooking_saysNothingWasCharged() {
        assertThat(templates.render(by(NotificationRequest.Kind.CANCELLATION, "COMPLIMENTARY")).text()).contains("Nothing was charged").doesNotContain("your card");
        assertThat(templates.render(by(NotificationRequest.Kind.TICKET, "COMPLIMENTARY")).text()).contains("Complimentary (no charge)");
    }

    @Test
    void aCardCancellation_keepsTheCardRefundWords() {
        assertThat(templates.render(by(NotificationRequest.Kind.CANCELLATION, "CARD")).text()).contains("is being refunded to your card");
    }
}
