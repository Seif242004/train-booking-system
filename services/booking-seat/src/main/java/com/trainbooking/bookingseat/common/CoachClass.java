package com.trainbooking.bookingseat.common;

/** The class of a coach and of its seats. The same three values as in Train & Schedule. */
// Stored as text in the database (trip_coach.coach_class and trip_fare.coach_class), so renaming a constant needs a data migration.
public enum CoachClass {
    FIRST,
    SECOND,
    THIRD
}
