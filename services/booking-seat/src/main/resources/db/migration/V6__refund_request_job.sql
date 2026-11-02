-- Slice 11: a cancellation asks Payment for the refund through the outbox too, so the refund request is never lost and never blocks the
-- cancellation. The job kinds of the outbox gain REFUND_REQUEST.
ALTER TABLE outbox DROP CONSTRAINT ck_outbox_kind;
ALTER TABLE outbox ADD CONSTRAINT ck_outbox_kind CHECK (kind IN ('TICKET_EMAIL', 'TICKET_INVALIDATE', 'REFUND_REQUEST', 'CANCELLATION_EMAIL', 'REFUND_EMAIL'));
