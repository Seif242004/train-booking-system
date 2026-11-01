package com.trainbooking.payment.payment;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.trainbooking.payment.testsupport.DatabaseTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** The internal endpoints: only a service holding the shared key may call them, and the input is validated. */
class PaymentControllerDatabaseTest extends DatabaseTest {

    private static final String BODY = "{\"bookingId\":7,\"amount\":230.00,\"currency\":\"EGP\"}";

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder create(String body) {
        return post("/internal/payments").contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void withoutTheKey_orWithAWrongOne_everyInternalRouteIs401() throws Exception {
        mvc.perform(create(BODY)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("invalid_api_key"));
        mvc.perform(create(BODY).header("X-Internal-Api-Key", "wrong")).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/payments/7/cancel")).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/payments/7/invalidate")).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/refunds").contentType(MediaType.APPLICATION_JSON).content("{\"bookingId\":7}")).andExpect(status().isUnauthorized());
        mvc.perform(create(BODY).header("Authorization", "Bearer a.user.token")).andExpect(status().isUnauthorized()); // a user's token is not a key
    }

    @Test
    void create_returnsTheSecretAndThePublishableKey_butNeverTheSecretKey() throws Exception {
        mvc.perform(create(BODY).header("X-Internal-Api-Key", INTERNAL_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientSecret").value("pi_fake_1_secret"))
                .andExpect(jsonPath("$.publishableKey").value("pk_test_fake"))
                .andExpect(jsonPath("$.amount").value(230.00))
                .andExpect(jsonPath("$.currency").value("EGP"))
                .andExpect(jsonPath("$.secretKey").doesNotExist());
    }

    @Test
    void create_validatesTheInput() throws Exception {
        mvc.perform(create("{}").header("X-Internal-Api-Key", INTERNAL_KEY))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("validation_failed"));
        mvc.perform(create("{\"bookingId\":7,\"amount\":0,\"currency\":\"EGP\"}").header("X-Internal-Api-Key", INTERNAL_KEY)).andExpect(status().isBadRequest());
        mvc.perform(create("{\"bookingId\":7,\"amount\":10.123,\"currency\":\"EGP\"}").header("X-Internal-Api-Key", INTERNAL_KEY)).andExpect(status().isBadRequest());
        mvc.perform(create("{\"bookingId\":7,\"amount\":10,\"currency\":\"USD\"}").header("X-Internal-Api-Key", INTERNAL_KEY)).andExpect(status().isBadRequest());
        mvc.perform(create("not json").header("X-Internal-Api-Key", INTERNAL_KEY))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("malformed_request"));
    }

    @Test
    void create_afterTheHoldEnded_is409() throws Exception {
        mvc.perform(post("/internal/payments/7/cancel").header("X-Internal-Api-Key", INTERNAL_KEY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("NONE"));

        mvc.perform(create(BODY).header("X-Internal-Api-Key", INTERNAL_KEY))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("payment_closed"));
    }

    @Test
    void whenStripeIsDown_theAnswerIs503_notAnInventedOutcome() throws Exception {
        mvc.perform(create(BODY).header("X-Internal-Api-Key", INTERNAL_KEY)).andExpect(status().isOk());
        stripe.down(true);

        mvc.perform(post("/internal/payments/7/cancel").header("X-Internal-Api-Key", INTERNAL_KEY))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error.code").value("stripe_unavailable"));
    }

    @Test
    void invalidate_answersTheOutcome() throws Exception {
        mvc.perform(create(BODY).header("X-Internal-Api-Key", INTERNAL_KEY)).andExpect(status().isOk());

        mvc.perform(post("/internal/payments/7/invalidate").header("X-Internal-Api-Key", INTERNAL_KEY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("CANCELLED"));
    }

    @Test
    void anythingElse_isClosed() throws Exception {
        mvc.perform(get("/payments/7")).andExpect(status().is4xxClientError());
        mvc.perform(get("/internal/unknown").header("X-Internal-Api-Key", INTERNAL_KEY)).andExpect(status().isNotFound());
    }
}
