package com.trainbooking.trainschedule.coach.model;

/**
 * Passenger class of a coach. The SRS says "class" but gives no values; these three are our decision.
 * Fares are later set per route segment per class, and every seat has the class of its coach.
 */
public enum CoachClass {
    FIRST,
    SECOND,
    THIRD
}
