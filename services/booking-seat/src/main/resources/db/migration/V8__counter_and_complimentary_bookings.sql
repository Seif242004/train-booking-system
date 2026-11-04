-- Counter and complimentary bookings (docs/design/COUNTER-BOOKING.md): an admin confirms a hold without a Stripe payment.
-- payment_method: CARD for every existing booking (the Stripe path), COUNTER (paid in person) or COMPLIMENTARY (no charge, a reason is required).
-- charged_amount: what was really taken: the fare for COUNTER, 0 for COMPLIMENTARY, empty for CARD (it equals amount). amount stays the fare.
-- issued_by / issued_at / issue_note: the admin who issued it, when, and the receipt note or the reason (the audit of the rule).
-- walk_in_*: a passenger without an account; the booking then belongs to the issuing admin.
ALTER TABLE booking ADD (
    payment_method       VARCHAR2(15 CHAR) DEFAULT 'CARD' NOT NULL,
    charged_amount       NUMBER(12, 2),
    issued_by            VARCHAR2(64 CHAR),
    issued_at            TIMESTAMP WITH TIME ZONE,
    issue_note           VARCHAR2(500 CHAR),
    walk_in_name         VARCHAR2(200 CHAR),
    walk_in_phone        VARCHAR2(40 CHAR),
    walk_in_email        VARCHAR2(254 CHAR),
    counter_refunded_by  VARCHAR2(64 CHAR),
    counter_refunded_at  TIMESTAMP WITH TIME ZONE
);

ALTER TABLE booking ADD CONSTRAINT ck_booking_method CHECK (payment_method IN ('CARD', 'COUNTER', 'COMPLIMENTARY'));
-- A non-card booking always says who issued it; a complimentary one always says why.
ALTER TABLE booking ADD CONSTRAINT ck_booking_issued CHECK (payment_method = 'CARD' OR issued_by IS NOT NULL);

ALTER TABLE booking DROP CONSTRAINT ck_booking_refund;
ALTER TABLE booking ADD CONSTRAINT ck_booking_refund CHECK (refund_status IN
    ('NONE', 'REFUND_PENDING', 'REFUNDED', 'REFUND_FAILED', 'NOT_APPLICABLE', 'MANUAL_REFUND_DUE', 'REFUNDED_AT_COUNTER'));
