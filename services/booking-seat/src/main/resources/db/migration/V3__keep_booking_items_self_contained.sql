-- A booking is history once it is paid, cancelled or expired: it must keep making sense even if Train & Schedule later pushes a
-- snapshot with other seat numbers or coaches. So the lines of a booking carry what the passenger was shown (the seat number, the
-- coach and its class) instead of looking it up in the copy of the trip.
ALTER TABLE booking_item ADD (
    seat_number  VARCHAR2(10 CHAR),   -- for a SEAT
    coach_code   VARCHAR2(10 CHAR),
    coach_class  VARCHAR2(10 CHAR)
);
