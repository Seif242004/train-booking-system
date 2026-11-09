package com.trainbooking.payment.stripe;

import com.stripe.StripeClient;
import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.RefundCreateParams;
import java.math.BigDecimal;

/**
 * The real Stripe: a thin translation between {@link StripeGateway} and the official client. Card data never passes through here;
 * the browser sends it straight to Stripe and only the client secret of the intent travels to the payment page.
 */
// Not a Spring-scanned bean on its own: StripeConfig creates it when a secret key exists.
public class StripeApiGateway implements StripeGateway {

    /** Stripe's code for "this intent is not in a state where that can be done" (for example cancelling one that succeeded). */
    // Constant used to recognise Stripe's refusal to cancel an intent that already succeeded or is processing.
    private static final String UNEXPECTED_STATE = "payment_intent_unexpected_state";

    private final StripeClient client;

    public StripeApiGateway(String secretKey) {
        this.client = new StripeClient(secretKey);
    }

    // @Override: this method implements the method of the same name in StripeGateway. (Repeated annotations below mean the same.)
    @Override
    public Intent createIntent(BigDecimal amount, String currency, long bookingId, String idempotencyKey) {
        PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                .setAmount(minorUnits(amount))
                .setCurrency(currency.toLowerCase())
                // Cards only (CLAUDE.md, SRS 4.2): a card is confirmed at once or declined, while delayed methods (bank debits, vouchers)
                // leave an intent in "processing" for days, where it cannot be cancelled and would keep seats held.
                .addAllowedPaymentMethodType(PaymentIntentCreateParams.AllowedPaymentMethodType.CARD)
                .putMetadata("bookingId", Long.toString(bookingId))
                .build();
        return call("create a payment intent", () -> toIntent(client.v1().paymentIntents()
                .create(params, RequestOptions.builder().setIdempotencyKey(idempotencyKey).build())));
    }

    @Override
    public Intent retrieveIntent(String intentId) {
        return call("read a payment intent", () -> toIntent(client.v1().paymentIntents().retrieve(intentId)));
    }

    @Override
    public Intent cancelIntent(String intentId) {
        try {
            return toIntent(client.v1().paymentIntents().cancel(intentId));
        } catch (InvalidRequestException e) {
            if (UNEXPECTED_STATE.equals(e.getCode())) {
                // Too late to cancel: it succeeded, or is being processed. Read the state it is in; the caller decides what that means.
                return retrieveIntent(intentId);
            }
            throw new StripeCallException("Stripe refused to cancel a payment intent: " + e.getMessage(), e);
        } catch (StripeException e) {
            throw new StripeCallException("Stripe could not cancel a payment intent: " + e.getMessage(), e);
        }
    }

    @Override
    public RefundResult createRefund(String intentId, BigDecimal amount, String currency, String idempotencyKey) {
        RefundCreateParams params = RefundCreateParams.builder().setPaymentIntent(intentId).setAmount(minorUnits(amount)).build();
        return call("create a refund", () -> {
            Refund refund = client.v1().refunds().create(params, RequestOptions.builder().setIdempotencyKey(idempotencyKey).build());
            return new RefundResult(refund.getId(), refund.getStatus(), refund.getFailureReason());
        });
    }

    private static Intent toIntent(PaymentIntent intent) {
        return new Intent(intent.getId(), intent.getStatus(), intent.getClientSecret());
    }

    /** EGP, like most currencies, has two decimals: Stripe counts in the smallest unit (piastres). */
    // Stripe wants the smallest unit: EGP 12.50 -> 1250 piastres. longValueExact() throws if there were more than 2 decimals,
    // which is safer than rounding money silently.
    static long minorUnits(BigDecimal amount) {
        return amount.movePointRight(2).longValueExact();
    }

    private interface StripeCall<T> {
        T run() throws StripeException;
    }

    // One place that turns Stripe failures into our two exceptions: StripeCallException (no answer, retry later) and
    // StripeRejectedException (a 4xx refusal for good, except 429 and idempotency_key_in_use).
    private static <T> T call(String what, StripeCall<T> call) {
        try {
            return call.run();
        } catch (ApiConnectionException e) {
            throw new StripeCallException("Stripe could not be reached to " + what + ".", e);
        } catch (StripeException e) {
            // A 4xx other than rate limiting (429) is Stripe saying "no" to this request: trying again cannot help.
            Integer status = e.getStatusCode();
            // "idempotency_key_in_use" means the same request is still being processed: not a refusal, just try again.
            if (status != null && status >= 400 && status < 500 && status != 429 && !"idempotency_key_in_use".equals(e.getCode())) {
                throw new StripeRejectedException("Stripe refused to " + what + ": " + e.getMessage(), e);
            }
            throw new StripeCallException("Stripe failed to " + what + ": " + e.getMessage(), e);
        }
    }
}
