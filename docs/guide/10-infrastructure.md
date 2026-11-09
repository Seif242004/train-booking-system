# 10. Infrastructure and configuration

## Purpose
Everything that is not application code but makes the system run: the Docker Compose stack, the Docker images of the Spring services, the
Gateway and the web app, the custom Keycloak image and its login theme, the Oracle init script that creates the schemas, the Gradle root build,
the `application.properties` of the five services and the `.env.example` key list.

Run it all with `docker compose --profile dev up -d --build` (the `dev` profile adds Mailpit and the Stripe CLI). Compose reads the secrets from
the root `.env`, which is git-ignored. `.env.example` lists the key names and what each is for, never a value.

## Folder structure
```
docker-compose.yml          the whole stack
.env.example                key names and purposes
.dockerignore               keeps every .env, node_modules and build output out of the images
build.gradle, settings.gradle   root Gradle build (shared by the five Spring services)
infra/
  docker/    spring.Dockerfile, gateway.Dockerfile, frontend.Dockerfile, entrypoint-java.sh, nginx.conf
  keycloak/  Dockerfile, realm-dev.json, themes/train-booking/ (login theme)
  oracle/init/01-create-schemas.sh
services/*/src/main/resources/application.properties
```

## Every file, one line each
| File | What it does |
|---|---|
| `docker-compose.yml` | Defines the containers, their ports, volumes, healthchecks and environment (see the table below). |
| `.env.example` | Lists every secret and setting Compose and the services read; copy to `.env` and fill it. |
| `build.gradle` | Root build: pins the Spring Boot plugin version once, sets group and version, and gives every service Java 21, UTF-8 and JUnit 5. |
| `settings.gradle` | Names the project and lists the five Gradle subprojects under `services/`. |
| `infra/docker/spring.Dockerfile` | One two-stage recipe for all five Spring services (build the jar with a JDK, run it on a JRE with the OpenTelemetry agent). |
| `infra/docker/entrypoint-java.sh` | Container start script: builds the OpenObserve login header for the agent, then starts Java. |
| `infra/docker/gateway.Dockerfile` | Image of the Express Gateway (Node 22, production dependencies, runs as `node`). |
| `infra/docker/frontend.Dockerfile` | Two-stage image of the web app: Vite builds, nginx serves. |
| `infra/docker/nginx.conf` | nginx server for the web app: SPA fallback and cache rules. |
| `infra/keycloak/Dockerfile` | Keycloak plus the Oracle JDBC driver (checksum-pinned), built for the `oracle` database vendor. |
| `infra/keycloak/themes/train-booking/login/theme.properties` | Declares the theme: parent `keycloak.v2`, our stylesheet, light mode only. |
| `.../login/messages/messages_en.properties` | Replaces the English wording of the login pages (titles, buttons, links). |
| `.../login/resources/css/train-booking.css` | The look of the login pages: design tokens, split layout, fields, buttons, messages, phone layout, reduced motion. |
| `infra/oracle/init/01-create-schemas.sh` | First-start script of the Oracle container: one tablespace and one user (schema) per service, plus the optional `_test` schemas. |
| `services/*/src/main/resources/application.properties` | Port, database, security, neighbour addresses and secrets-by-placeholder of each Spring service. |

Not commented on purpose: `infra/keycloak/realm-dev.json` (JSON, and the realm file is imported as it is), the theme `README.md`, the images,
and the Flyway migrations (their checksums must not change).

## The Compose stack
Ports are published on `127.0.0.1` only. A service reaches another by its service name on the private network.

| Service | Image / build | Host port | Role | Waits for |
|---|---|---|---|---|
| `oracle` | `gvenzl/oracle-free:23-slim` | 1521 | One database `FREEPDB1`, one schema per service; runs `infra/oracle/init` on an empty volume | - |
| `keycloak` | built from `infra/keycloak` | 8180 (container 8080) | Login, registration, tokens (realm `train-booking`, imported from `realm-dev.json`), data in the `keycloak` schema | oracle healthy |
| `openobserve` | `openobserve/openobserve:v1.1.2` | 5080 | Logs, traces, audit trail (retention 14 days) | - |
| `train-schedule` | `spring.Dockerfile`, `SERVICE=train-schedule` | none | Timetable data | oracle healthy, openobserve started |
| `booking-seat` | same recipe | none | Availability, holds, bookings, live updates | same |
| `payment` | same recipe | none | Stripe payments and refunds | same |
| `ticket` | same recipe | none | PDF ticket with QR code | same |
| `notification` | same recipe | none | Emails with retries | same |
| `gateway` | `gateway.Dockerfile` | 8080 | Single entry point | openobserve started |
| `frontend` | `frontend.Dockerfile` | 5173 | The web app (nginx) | - |
| `mailpit` (profile `dev`) | `axllent/mailpit` | 1025 SMTP, 8025 web | Catches all emails | - |
| `stripe-cli` (profile `dev`) | `stripe/stripe-cli` | none | Forwards Stripe test events to the Gateway webhook route | gateway |

Volumes: `oracle-data` (database files) and `openobserve-data`. `docker compose down` keeps them; `docker compose down -v` deletes them and
makes Oracle re-run the init script and Keycloak re-import the realm.

### How the pieces are shared
- `x-spring-service` is a YAML anchor: restart policy, `depends_on` and the common environment. Each Spring service merges it with `<<: *spring-service`
  and `<<: *spring-env`, then adds its own variables.
- The common environment turns on JSON console logs (`LOGGING_STRUCTURED_FORMAT_CONSOLE=logstash`), points the OpenTelemetry agent at OpenObserve,
  copies the logging context (user, booking, trip ids) into every log record, and passes the shared `INTERNAL_API_KEY` and the OpenObserve login.
- `KEYCLOAK_ISSUER` is the browser's address (`http://localhost:8180/...`) because it must equal the `iss` inside the token; the keys are
  fetched from the internal address through `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI` (services) or `KEYCLOAK_JWKS_URI` (Gateway).
- Required values use `${NAME:?message}`: Compose refuses to start with a clear message if one is missing. Optional ones use `${NAME:-}`.

## Docker images
**`spring.Dockerfile`** (build from the repository root):
1. Stage `build` (JDK 21): copy `gradlew`, `settings.gradle`, `build.gradle`, `gradle/`, `services/`; run `./gradlew :services:<SERVICE>:bootJar -x test`
   with a Gradle cache mount (tests run separately with `./gradlew test`).
2. Stage run (JRE 21): download the OpenTelemetry Java agent (version 2.33.1) from Maven Central, copy the jar and `entrypoint-java.sh`, create a
   non-root user `app`, and set the entrypoint.

**`entrypoint-java.sh`**: if `OPENOBSERVE_USER` and `OTEL_EXPORTER_OTLP_ENDPOINT` are set, it builds `Authorization=Basic <base64(user:password)>`
(URL-encoded) as `OTEL_EXPORTER_OTLP_HEADERS`; otherwise it sets `OTEL_SDK_DISABLED=true`. It ends with `exec java -javaagent:... -jar app.jar`, so
Java is the main process and gets the stop signal.

**`gateway.Dockerfile`**: Node 22 alpine, `npm ci --omit=dev`, only `gateway/src` copied, runs as user `node`, `CMD node src/server.js`.

**`frontend.Dockerfile`**: stage 1 installs and runs `npm run build` with the build arguments `VITE_KEYCLOAK_URL`, `VITE_GATEWAY_URL`,
`VITE_SUPPORT_EMAIL` (compiled into the bundle, so public); stage 2 is nginx serving `dist/` on port 5173 with `nginx.conf`.

**`nginx.conf`**: `try_files $uri /index.html` makes every unknown path load the app (React Router decides); `/assets/` (hashed names) is cached
for a year; `index.html` is `no-store`.

**`infra/keycloak/Dockerfile`**: official Keycloak 26.8.1 image, adds `ojdbc17` and `orai18n` (Oracle driver and character sets) to `providers/`
with SHA-256 checks, sets `KC_DB=oracle` and runs `kc.sh build`. At run time Compose starts it with `start-dev --import-realm`.

## Oracle init script
`01-create-schemas.sh` runs once, on the first start with an empty `oracle-data` volume. For each service it runs `create_schema NAME PASSWORD`,
which connects with `sqlplus / as sysdba`, switches to `FREEPDB1`, creates a tablespace `ts_<name>` (50 MB, auto-extend to 1 GB) and a user with
unlimited quota on it, and grants only `CREATE SESSION, TABLE, SEQUENCE, VIEW`. No grants across schemas: a service cannot read another service's
tables. Schemas: `train_schedule`, `booking_seat`, `payment`, `ticket`, `notification`, `keycloak`, and the optional `booking_seat_test`,
`payment_test`, `ticket_test`, `notification_test` (created only if their `*_TEST_DB_PASSWORD` is set). An empty password stops the script.

## Spring configuration (`application.properties`)
| Service | Port | Properties of interest |
|---|---|---|
| train-schedule | 8081 | datasource `train_schedule`; Keycloak issuer; `trainbooking.booking-seat.url`, `trainbooking.internal-api-key`; `trainbooking.timezone` (env `TRAINBOOKING_TIMEZONE`) |
| booking-seat | 8082 | datasource `booking_seat`; Keycloak issuer; `trainbooking.ws.allowed-origin`; URLs of payment, ticket, notification, train-schedule; internal key; timezone |
| payment | 8083 | datasource `payment`; internal key; Booking URL; `stripe.secret-key`, `stripe.publishable-key`, `stripe.webhook-secret`; outbox delay; also reads the root `.env` |
| ticket | 8084 | datasource `ticket`; internal key; `trainbooking.ticket.signing-key` (no default); timezone |
| notification | 8085 | datasource `notification`; internal key; SMTP (`MAIL_*`), timeouts, sender; `trainbooking.notification.fixed-delay-ms`; timezone |

Common rules: `${NAME:default}` reads an environment variable with a default, `${NAME}` has no default; `spring.config.import=optional:file:.env[.properties]`
loads a local `.env` for runs from Gradle; environment variables win, which is how Compose replaces `localhost` with service names. Secrets
without a default (database password, internal key, ticket signing key) make the service fail at start when missing.

## Gradle
`settings.gradle` includes the five subprojects. `build.gradle` declares the Spring Boot plugin (`apply false`) once, sets `group`/`version`,
and in `subprojects` configures Maven Central, a Java 21 toolchain, UTF-8 and JUnit Platform. Each service's own `build.gradle` lists its libraries.

## Keycloak login theme
`theme.properties` makes `train-booking` inherit `keycloak.v2` and add `css/train-booking.css`; `messages_en.properties` changes wording only.
The CSS defines the tokens on `:root` (same names as the web app), sets PatternFly variables, builds a split layout (photo panel left, form
right; a band above the form below 900 px), restyles fields, buttons and alerts, keeps 44 px touch targets, and switches off animation for
`prefers-reduced-motion`. The theme folder is mounted read-only into Keycloak; `start-dev` does not cache themes, so edits show after a reload.

## Known limits
- `start-dev` and `KC_HOSTNAME=http://localhost:8180` are for local use only; production would need HTTPS, a real hostname and a different realm file.
- The comment above `stripe-cli` in `docker-compose.yml` still says the Gateway runs on the host; in the current stack it is a container and the
  CLI forwards to `http://gateway:8080/webhooks/payment`.
- The Spring image copies `...-0.0.1-SNAPSHOT.jar`, so changing the version in `build.gradle` breaks the Docker build until the Dockerfile is updated.
- `stripe/stripe-cli:latest` and `axllent/mailpit:latest` are not pinned; the other images are.
- The OpenTelemetry agent is downloaded at build time without a checksum (the Keycloak drivers have one).
- The `train-schedule` timezone variable is `TRAINBOOKING_TIMEZONE`, the other services use `TIMEZONE`.
- The `_test` schema creation relies on a fresh volume; for an existing volume the schema must be created by hand.
- `entrypoint-java.sh` is the only LF file of this group (forced by `.gitattributes`, because a CRLF shell script fails inside Linux).
- The root `build.gradle` comments and `nginx.conf` comments are not covered by the comments-only checker (it does not know those file types);
  they were verified by stripping comment lines and comparing.
