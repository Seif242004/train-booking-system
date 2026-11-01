package com.trainbooking.payment.payment.model;

/**
 * The life of the payment of one booking.
 * <ul>
 *   <li>{@code CREATING}: the row exists and Stripe is being asked for an intent (nobody has a client secret yet);</li>
 *   <li>{@code PENDING}: the intent exists and can be paid;</li>
 *   <li>{@code PROCESSING}: Stripe is processing the payment (cards only, rarely): it can still succeed or fail;</li>
 *   <li>{@code SUCCEEDED}: the money is taken (only a refund can undo it);</li>
 *   <li>{@code CANCELLED}: the intent is cancelled, or never got to exist (a tombstone, see {@link Payment#ended()}).</li>
 * </ul>
 */
public enum PaymentStatus {
    CREATING, PENDING, PROCESSING, SUCCEEDED, CANCELLED
}
