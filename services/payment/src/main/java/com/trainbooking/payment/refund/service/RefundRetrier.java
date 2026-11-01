package com.trainbooking.payment.refund.service;

import com.trainbooking.payment.refund.model.Refund;
import com.trainbooking.payment.refund.repository.RefundRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Asks Stripe again for refunds it could not be reached for. The Stripe idempotency key (the refund id) makes a repeat harmless. */
@Component
public class RefundRetrier {

    private static final Logger log = LoggerFactory.getLogger(RefundRetrier.class);

    private final RefundRepository refunds;
    private final RefundService service;
    private final Clock clock;

    public RefundRetrier(RefundRepository refunds, RefundService service, Clock clock) {
        this.refunds = refunds;
        this.service = service;
        this.clock = clock;
    }

    /** @return how many refunds were tried (the scheduler ignores it; tests use it) */
    public int retryDue() {
        int tried = 0;
        for (Refund refund : refunds.findDueWithoutStripeRefund(OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC), 20)) {
            try {
                service.attempt(refund);
                tried++;
            } catch (RuntimeException e) {
                log.warn("Retry of refund {} failed: {}", refund.id(), e.getMessage()); // the next sweep tries again
            }
        }
        return tried;
    }
}
