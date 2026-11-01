-- Booking & Seat schema, first migration. Runs as the booking_seat user, so the tables land in that schema.
--
-- Part 1 is Booking's own immutable copy of a trip (SRS section 8, "trip_snapshot (copy)"), filled by the snapshot
-- that Train & Schedule pushes. Booking never joins to Train & Schedule tables (CLAUDE.md). The ids in the copy are
-- the Train & Schedule ids, so none of these columns is an identity column.
-- Part 2 is the inventory: seat_reservation and standing_usage (SRS section 5). It stays empty until holds exist.

CREATE TABLE trip (
    id              NUMBER(19) NOT NULL,                -- the id of the trip in Train & Schedule
    departure_date  DATE       NOT NULL,                -- the date the trip leaves its first stop
    train_number    VARCHAR2(20 CHAR)  NOT NULL,
    train_name      VARCHAR2(100 CHAR) NOT NULL,
    train_type      VARCHAR2(50 CHAR)  NOT NULL,
    route_name      VARCHAR2(100 CHAR) NOT NULL,
    -- OPEN: bookable. CANCELLING / CANCELLED: set by the trip cancellation (slice 12), never by a snapshot.
    status          VARCHAR2(10 CHAR) DEFAULT 'OPEN' NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT pk_trip PRIMARY KEY (id),
    CONSTRAINT ck_trip_status CHECK (status IN ('OPEN', 'CANCELLING', 'CANCELLED'))
);

CREATE TABLE trip_stop (
    trip_id         NUMBER(19) NOT NULL,
    stop_index      NUMBER(5)  NOT NULL,                -- stops 0..N-1; segment k is the leg from stop k to stop k+1
    station_id      NUMBER(19) NOT NULL,
    station_code    VARCHAR2(10 CHAR)  NOT NULL,
    station_name    VARCHAR2(100 CHAR) NOT NULL,
    arrival_time    TIMESTAMP WITH TIME ZONE,           -- empty at the first stop
    departure_time  TIMESTAMP WITH TIME ZONE,           -- empty at the last stop
    CONSTRAINT pk_trip_stop PRIMARY KEY (trip_id, stop_index),
    CONSTRAINT fk_trip_stop_trip FOREIGN KEY (trip_id) REFERENCES trip (id) ON DELETE CASCADE,
    CONSTRAINT ck_trip_stop_index CHECK (stop_index >= 0)
);

CREATE TABLE trip_coach (
    trip_id    NUMBER(19) NOT NULL,
    coach_id   NUMBER(19) NOT NULL,
    code       VARCHAR2(10 CHAR) NOT NULL,
    class      VARCHAR2(10 CHAR) NOT NULL,
    position   NUMBER(5)         NOT NULL,
    -- The standing capacity of the coach is not stored here: its copy is standing_usage.capacity (SRS section 5.2).
    CONSTRAINT pk_trip_coach PRIMARY KEY (trip_id, coach_id),
    CONSTRAINT fk_trip_coach_trip FOREIGN KEY (trip_id) REFERENCES trip (id) ON DELETE CASCADE,
    CONSTRAINT ck_trip_coach_class CHECK (class IN ('FIRST', 'SECOND', 'THIRD'))
);

CREATE TABLE trip_seat (
    trip_id      NUMBER(19) NOT NULL,
    seat_id      NUMBER(19) NOT NULL,
    coach_id     NUMBER(19) NOT NULL,
    seat_number  VARCHAR2(10 CHAR) NOT NULL,
    seat_row     NUMBER(5)         NOT NULL,            -- ROW and COLUMN are Oracle reserved words (as in Train & Schedule)
    seat_column  VARCHAR2(3 CHAR)  NOT NULL,
    class        VARCHAR2(10 CHAR) NOT NULL,            -- the class of the coach of the seat
    CONSTRAINT pk_trip_seat PRIMARY KEY (trip_id, seat_id),
    CONSTRAINT fk_trip_seat_trip FOREIGN KEY (trip_id) REFERENCES trip (id) ON DELETE CASCADE,
    CONSTRAINT fk_trip_seat_coach FOREIGN KEY (trip_id, coach_id) REFERENCES trip_coach (trip_id, coach_id) ON DELETE CASCADE,
    CONSTRAINT ck_trip_seat_class CHECK (class IN ('FIRST', 'SECOND', 'THIRD'))
);

CREATE TABLE trip_fare (
    trip_id        NUMBER(19) NOT NULL,
    segment_index  NUMBER(5)  NOT NULL,
    class          VARCHAR2(10 CHAR) NOT NULL,
    amount         NUMBER(10, 2)     NOT NULL,
    currency       VARCHAR2(3 CHAR)  NOT NULL,
    CONSTRAINT pk_trip_fare PRIMARY KEY (trip_id, segment_index, class),
    CONSTRAINT fk_trip_fare_trip FOREIGN KEY (trip_id) REFERENCES trip (id) ON DELETE CASCADE,
    CONSTRAINT ck_trip_fare_class CHECK (class IN ('FIRST', 'SECOND', 'THIRD')),
    CONSTRAINT ck_trip_fare_amount CHECK (amount > 0)
);

-- SRS section 5.1. One row per seat per segment that is held or booked: the PRIMARY KEY makes double booking
-- physically impossible (ORA-00001 means "seat taken"). expires_at is set while HELD and empty once CONFIRMED.
-- TODO(slice 7): booking_id gets its foreign key to booking(id) in the migration that creates the booking table.
-- The FK to trip_seat means that a seat with reservations cannot be removed from the copy (ORA-02292), which is how
-- a capacity change that conflicts with holds or bookings is refused (FR-A9).
CREATE TABLE seat_reservation (
    trip_id        NUMBER(19) NOT NULL,
    seat_id        NUMBER(19) NOT NULL,
    segment_index  NUMBER(5)  NOT NULL,
    booking_id     NUMBER(19) NOT NULL,
    expires_at     TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_seat_reservation PRIMARY KEY (trip_id, seat_id, segment_index),
    CONSTRAINT fk_seat_reservation_seat FOREIGN KEY (trip_id, seat_id) REFERENCES trip_seat (trip_id, seat_id)
);

-- SRS section 5.2. A counter per coach per segment, with Booking's own copy of the standing capacity of the coach.
-- Rows exist only for coaches that have standing capacity. A hold increases `used` with a conditional UPDATE
-- (WHERE used + :qty <= capacity); the CHECK below is the last line of defence, and it also stops a capacity change
-- from dropping below the places already used.
CREATE TABLE standing_usage (
    trip_id        NUMBER(19) NOT NULL,
    coach_id       NUMBER(19) NOT NULL,
    segment_index  NUMBER(5)  NOT NULL,
    capacity       NUMBER(10) NOT NULL,
    used           NUMBER(10) DEFAULT 0 NOT NULL,
    CONSTRAINT pk_standing_usage PRIMARY KEY (trip_id, coach_id, segment_index),
    CONSTRAINT fk_standing_usage_coach FOREIGN KEY (trip_id, coach_id) REFERENCES trip_coach (trip_id, coach_id) ON DELETE CASCADE,
    CONSTRAINT ck_standing_cap CHECK (used <= capacity),
    CONSTRAINT ck_standing_nonneg CHECK (used >= 0 AND capacity >= 0)
);

-- FR-A10: every change is written to an audit log (the snapshot receiver records who triggered it).
CREATE TABLE audit_log (
    id          NUMBER(19) GENERATED BY DEFAULT AS IDENTITY NOT NULL,
    admin_id    VARCHAR2(64 CHAR) NOT NULL,   -- the Keycloak user id (JWT sub) of the admin, a plain value
    entity      VARCHAR2(50 CHAR) NOT NULL,
    entity_id   NUMBER(19)        NOT NULL,
    action      VARCHAR2(20 CHAR) NOT NULL,   -- CREATE, UPDATE or DELETE
    diff        CLOB,
    created_at  TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT pk_audit_log PRIMARY KEY (id)
);
