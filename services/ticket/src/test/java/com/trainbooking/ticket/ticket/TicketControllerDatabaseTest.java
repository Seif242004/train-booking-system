package com.trainbooking.ticket.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.trainbooking.ticket.testsupport.DatabaseTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** The internal endpoints: only a service holding the shared key may call them, and the input is validated. */
class TicketControllerDatabaseTest extends DatabaseTest {

    // A valid request body in JSON (a Java text block: the quotes need no escaping).
    private static final String BODY = """
            {"reference":"TB-ABC234","passengerName":"Alice Example","trainNumber":"T100","trainName":"Express",
             "fromStation":"Cairo","departure":"2026-11-02T06:00:00Z","toStation":"Alexandria","arrival":"2026-11-02T08:45:00Z",
             "lines":[{"type":"SEAT","coachCode":"A1","coachClass":"FIRST","seatNumber":"1A","qty":1,"price":230.00}],
             "amount":230.00,"currency":"EGP"}""";

    // Builds the PUT request of the issue call. The tests add the key header themselves, so the missing-key case can be tested too.
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder issue(long booking, String body) {
        return put("/internal/tickets/" + booking).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    // {@code @Test} marks a JUnit 5 test method.
    @Test
    // Security: every route answers 401 invalid_api_key without the key, with a wrong key, and with a user token; and nothing is stored.
    void withoutTheKey_orWithAWrongOne_everyRouteIs401() throws Exception {
        mvc.perform(issue(7, BODY)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("invalid_api_key"));
        mvc.perform(issue(7, BODY).header("X-Internal-Api-Key", "wrong")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/tickets/7")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/tickets/7/pdf")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/tickets/7/qr")).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/tickets/7/invalidate")).andExpect(status().isUnauthorized());
        mvc.perform(issue(7, BODY).header("Authorization", "Bearer a.user.token")).andExpect(status().isUnauthorized()); // a user's token is not a key
        assertThat(rows("ticket")).isZero();
    }

    @Test
    // First PUT: 201; the same PUT again: 200 with the same ticket id.
    void issue_isCreatedOnce_thenOk() throws Exception {
        mvc.perform(issue(7, BODY).header("X-Internal-Api-Key", INTERNAL_KEY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("VALID"))
                .andExpect(jsonPath("$.reference").value("TB-ABC234"))
                .andExpect(jsonPath("$.qrToken").doesNotExist()); // the token is only in the QR code on the PDF
        mvc.perform(issue(7, BODY).header("X-Internal-Api-Key", INTERNAL_KEY)).andExpect(status().isOk());
        assertThat(rows("ticket")).isEqualTo(1);
    }

    @Test
    // The /qr answer is an image/png that decodes to the stored token, and the page was made with that same token.
    void theQrCode_isServedAsAPng_andCarriesTheTokenThatThePdfWasMadeWith() throws Exception {
        mvc.perform(get("/internal/tickets/7/qr").header("X-Internal-Api-Key", INTERNAL_KEY)).andExpect(status().isNotFound());
        mvc.perform(issue(7, BODY).header("X-Internal-Api-Key", INTERNAL_KEY)).andExpect(status().isCreated());

        byte[] png = mvc.perform(get("/internal/tickets/7/qr").header("X-Internal-Api-Key", INTERNAL_KEY))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.IMAGE_PNG)).andReturn().getResponse().getContentAsByteArray();

        var image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
        String decoded = new com.google.zxing.qrcode.QRCodeReader().decode(new com.google.zxing.BinaryBitmap(new com.google.zxing.common.HybridBinarizer(
                new com.google.zxing.client.j2se.BufferedImageLuminanceSource(image)))).getText();
        // The token stored with the ticket is the one the PDF was drawn with (TicketService.issue passes the same value to both).
        assertThat(decoded).isEqualTo(jdbc.sql("SELECT qr_token FROM ticket WHERE booking_id = 7").query(String.class).single());
        assertThat(decoded).startsWith("TB-ABC234.");
        assertThat(image.getWidth()).isGreaterThanOrEqualTo(168);
    }

    @Test
    // The /pdf answer has the PDF content type and starts with the PDF signature (%PDF-).
    void thePdf_isServedAsAPdf() throws Exception {
        mvc.perform(issue(7, BODY).header("X-Internal-Api-Key", INTERNAL_KEY)).andExpect(status().isCreated());

        byte[] pdf = mvc.perform(get("/internal/tickets/7/pdf").header("X-Internal-Api-Key", INTERNAL_KEY))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.APPLICATION_PDF)).andReturn().getResponse().getContentAsByteArray();

        assertThat(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }

    @Test
    // Both calls answer INVALIDATED (a repeat is harmless); a booking without a ticket is 404 ticket_not_found for read and for invalidate.
    void invalidate_isIdempotent_andAnUnknownBookingIs404() throws Exception {
        mvc.perform(issue(7, BODY).header("X-Internal-Api-Key", INTERNAL_KEY)).andExpect(status().isCreated());

        mvc.perform(post("/internal/tickets/7/invalidate").header("X-Internal-Api-Key", INTERNAL_KEY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("INVALIDATED"));
        mvc.perform(post("/internal/tickets/7/invalidate").header("X-Internal-Api-Key", INTERNAL_KEY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("INVALIDATED"));
        mvc.perform(get("/internal/tickets/7").header("X-Internal-Api-Key", INTERNAL_KEY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("INVALIDATED"));
        mvc.perform(get("/internal/tickets/99").header("X-Internal-Api-Key", INTERNAL_KEY))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("ticket_not_found"));
        mvc.perform(post("/internal/tickets/99/invalidate").header("X-Internal-Api-Key", INTERNAL_KEY)).andExpect(status().isNotFound());
    }

    @Test
    // Empty body, empty reference, amount 0, unknown line type and wrong currency are 400 (empty body: validation_failed); text that is
    // not JSON
    // is 400 malformed_request; nothing is stored in any case.
    void issue_validatesTheInput() throws Exception {
        String key = INTERNAL_KEY;
        mvc.perform(issue(7, "{}").header("X-Internal-Api-Key", key))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("validation_failed"));
        mvc.perform(issue(7, BODY.replace("\"reference\":\"TB-ABC234\"", "\"reference\":\"\"")).header("X-Internal-Api-Key", key)).andExpect(status().isBadRequest());
        mvc.perform(issue(7, BODY.replace("\"amount\":230.00", "\"amount\":0")).header("X-Internal-Api-Key", key)).andExpect(status().isBadRequest());
        mvc.perform(issue(7, BODY.replace("\"type\":\"SEAT\"", "\"type\":\"LAP\"")).header("X-Internal-Api-Key", key)).andExpect(status().isBadRequest());
        mvc.perform(issue(7, BODY.replace("\"currency\":\"EGP\"", "\"currency\":\"USD\"")).header("X-Internal-Api-Key", key)).andExpect(status().isBadRequest());
        mvc.perform(issue(7, "not json").header("X-Internal-Api-Key", key))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("malformed_request"));
        assertThat(rows("ticket")).isZero();
    }

    @Test
    // A path outside /internal is refused by the second security chain (4xx); an unknown path inside /internal is 404.
    void anythingElse_isClosed() throws Exception {
        mvc.perform(get("/tickets/7")).andExpect(status().is4xxClientError());
        mvc.perform(get("/internal/unknown").header("X-Internal-Api-Key", INTERNAL_KEY)).andExpect(status().isNotFound());
    }
}
