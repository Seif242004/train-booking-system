-- The hold starts at the first pick (Flow 3): a user has at most one active hold per trip, and further picks join it (SRS FR-H6).
-- The database enforces it, not only the code: a unique index over the user and the trip, but only for rows that are PENDING_PAYMENT
-- (the other rows give NULL in both columns, and Oracle does not index rows whose indexed columns are all NULL).
CREATE UNIQUE INDEX uk_booking_active_hold ON booking (
    CASE WHEN status = 'PENDING_PAYMENT' THEN user_id END,
    CASE WHEN status = 'PENDING_PAYMENT' THEN trip_id END
);

-- Set when the user continued to payment, so that a pick or a release after that first cancels the open payment at Stripe (SRS 4.2:
-- the amount of a payment must never differ from the booking, and an open payment is cancelled before the hold changes).
ALTER TABLE booking ADD (payment_started NUMBER(1) DEFAULT 0 NOT NULL);
