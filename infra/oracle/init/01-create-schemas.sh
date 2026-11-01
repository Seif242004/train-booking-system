#!/bin/bash
# Creates one Oracle user (schema) per service, each in its own tablespace.
#
# Runs once, as the image's first-start init step, against the pluggable database FREEPDB1.
# SQL cannot read environment variables, so this script reads the passwords and feeds them to
# SQL*Plus as substitution variables.
#
# Privileges are the minimum a service needs to run its own migrations: CREATE SESSION, TABLE,
# SEQUENCE and VIEW. There are no grants on another schema, so a service cannot see or touch
# another service's tables (CLAUDE.md: "A service never reaches into another service's schema").

# Subshell so that `set -e` and `exit` cannot leak into the entrypoint if it sources this file.
(
  set -e

  create_schema() {
    local name="$1" password="$2"

    if [ -z "$password" ]; then
      echo "Missing password for schema ${name}" >&2
      exit 1
    fi

    echo "Creating schema ${name}"
    # The password is quoted in the DDL so it may contain any character except a double quote.
    sqlplus -s / as sysdba <<SQL
WHENEVER SQLERROR EXIT FAILURE
SET VERIFY OFF
ALTER SESSION SET CONTAINER = FREEPDB1;

-- FREEPDB1 has no default datafile location, so CREATE TABLESPACE needs one (ORA-02236 otherwise).
-- It sits on the oracle-data volume; Oracle picks the file names (Oracle Managed Files).
ALTER SESSION SET db_create_file_dest = '/opt/oracle/oradata';

-- Own tablespace per schema: isolates storage and gives the quota below something to bound.
CREATE TABLESPACE ts_${name}
  DATAFILE SIZE 50M AUTOEXTEND ON NEXT 50M MAXSIZE 1G;

CREATE USER ${name} IDENTIFIED BY "${password}"
  DEFAULT TABLESPACE ts_${name}
  QUOTA UNLIMITED ON ts_${name};

GRANT CREATE SESSION, CREATE TABLE, CREATE SEQUENCE, CREATE VIEW TO ${name};
EXIT
SQL
  }

  create_schema train_schedule "${TRAIN_SCHEDULE_DB_PASSWORD}"
  create_schema booking_seat   "${BOOKING_SEAT_DB_PASSWORD}"
  # Throwaway schema for the Booking & Seat database tests, so they never touch the dev data. It is optional: it is
  # created only when its password is set, and a setup without it simply skips those tests.
  if [ -n "${BOOKING_SEAT_TEST_DB_PASSWORD:-}" ]; then
    create_schema booking_seat_test "${BOOKING_SEAT_TEST_DB_PASSWORD}"
  else
    echo "BOOKING_SEAT_TEST_DB_PASSWORD is not set: not creating the booking_seat_test schema (the Booking & Seat database tests will be skipped)"
  fi
  create_schema payment        "${PAYMENT_DB_PASSWORD}"
  # Throwaway schema for the Payment database tests (optional, like booking_seat_test).
  if [ -n "${PAYMENT_TEST_DB_PASSWORD:-}" ]; then
    create_schema payment_test "${PAYMENT_TEST_DB_PASSWORD}"
  else
    echo "PAYMENT_TEST_DB_PASSWORD is not set: not creating the payment_test schema (the Payment database tests will be skipped)"
  fi
  create_schema ticket         "${TICKET_DB_PASSWORD}"
  create_schema notification   "${NOTIFICATION_DB_PASSWORD}"
  # Keycloak stores its users in Oracle too (docs/ARCHITECTURE.md, Flow 1). It gets its own schema like the services.
  create_schema keycloak       "${KEYCLOAK_DB_PASSWORD}"
) || exit 1
