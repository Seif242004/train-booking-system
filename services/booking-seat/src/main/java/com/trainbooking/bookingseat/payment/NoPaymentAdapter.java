package com.trainbooking.bookingseat.payment;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * Used while there is no Payment service to ask: no payment can exist, so there is never anything to cancel. It steps aside as
 * soon as a real {@link PaymentPort} is a bean.
 */
@Component
@ConditionalOnMissingBean(value = PaymentPort.class, ignored = NoPaymentAdapter.class)
class NoPaymentAdapter implements PaymentPort {

    @Override
    public CancelOutcome cancelPending(long bookingId) {
        return CancelOutcome.NONE;
    }
}
