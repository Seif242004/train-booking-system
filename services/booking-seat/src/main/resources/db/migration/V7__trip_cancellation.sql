-- Slice 12: cancelling a whole trip (Flow 8). The trip stays CANCELLING until every booking on it is processed and Train & Schedule has
-- been told; a worker retries both. These columns keep what the retries need.
ALTER TABLE trip ADD (
    cancel_reason        VARCHAR2(500 CHAR),                        -- the admin's reason, used in the emails
    cancelled_by         VARCHAR2(64 CHAR),                         -- the admin (Keycloak sub) who cancelled the trip
    cancel_requested_at  TIMESTAMP WITH TIME ZONE,
    tns_marked           NUMBER(1) DEFAULT 0 NOT NULL               -- 1 once Train & Schedule accepted "this trip is cancelled"
);
