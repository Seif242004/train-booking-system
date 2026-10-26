package com.trainbooking.trainschedule.train.model;

/**
 * A train as stored and returned by the API (SRS section 8: id, number, name, type, status).
 * The database column is {@code train_number} because NUMBER is an Oracle reserved word; the JSON name stays {@code number}.
 */
public record Train(long id, String number, String name, String type, TrainStatus status) {
}
