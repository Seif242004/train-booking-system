package com.trainbooking.payment.testsupport;

import com.stripe.Stripe;

/** Builds the JSON of the Stripe events the tests deliver to the webhook. */
public final class Events {

    private Events() {
    }

    public static String paymentIntent(String eventId, String type, String intentId, String status) {
        return paymentIntent(eventId, type, intentId, status, null);
    }

    /** @param errorMessage the reason of a failed attempt ({@code last_payment_error.message}), or null */
    public static String paymentIntent(String eventId, String type, String intentId, String status, String errorMessage) {
        String error = errorMessage == null ? "null" : "{\"message\":\"" + errorMessage + "\",\"type\":\"card_error\"}";
        return "{\"id\":\"" + eventId + "\",\"object\":\"event\",\"api_version\":\"" + Stripe.API_VERSION + "\",\"created\":1762000000,"
                + "\"livemode\":false,\"type\":\"" + type + "\",\"data\":{\"object\":{\"id\":\"" + intentId + "\",\"object\":\"payment_intent\","
                + "\"status\":\"" + status + "\",\"amount\":23000,\"currency\":\"egp\",\"last_payment_error\":" + error + "}}}";
    }

    public static String refund(String eventId, String type, String refundId, String intentId, String status) {
        return "{\"id\":\"" + eventId + "\",\"object\":\"event\",\"api_version\":\"" + Stripe.API_VERSION + "\",\"created\":1762000000,"
                + "\"livemode\":false,\"type\":\"" + type + "\",\"data\":{\"object\":{\"id\":\"" + refundId + "\",\"object\":\"refund\","
                + "\"payment_intent\":\"" + intentId + "\",\"status\":\"" + status + "\",\"amount\":23000,\"currency\":\"egp\"}}}";
    }

    public static String other(String eventId, String type) {
        return "{\"id\":\"" + eventId + "\",\"object\":\"event\",\"api_version\":\"" + Stripe.API_VERSION + "\",\"created\":1762000000,"
                + "\"livemode\":false,\"type\":\"" + type + "\",\"data\":{\"object\":{\"id\":\"cus_1\",\"object\":\"customer\"}}}";
    }
}
