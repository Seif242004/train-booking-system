# One image recipe for the five Spring Boot services. Build from the repository root:
#   docker build -f infra/docker/spring.Dockerfile --build-arg SERVICE=booking-seat .
# (docker-compose.yml does this.) The OpenTelemetry Java agent is added to every image; it sends traces and logs to OpenObserve when
# OTEL_EXPORTER_OTLP_ENDPOINT is set (see entrypoint-java.sh) and does nothing otherwise.
# ---- Stage 1 (build): a full JDK compiles and packages the service. Nothing from this stage except the jar is kept in the final image.
FROM eclipse-temurin:21-jdk AS build
# WORKDIR sets the folder for the next commands (and creates it).
WORKDIR /src
# Copy the Gradle wrapper and the build files first, then the sources: Docker caches each step, so a source change does not repeat the
# earlier copies.
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
COPY services services
# ARG: a build argument, given by `--build-arg SERVICE=...` (Compose passes it). An ARG must be declared again in each stage that uses it.
ARG SERVICE
# The Gradle cache is a build cache mount, so rebuilding does not download everything again. Tests are run by `./gradlew test`, not here.
# `-x test` skips the tests. `bootJar` builds the executable Spring Boot jar of one subproject. --no-daemon: no background Gradle process.
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon --console=plain :services:${SERVICE}:bootJar -x test

# ---- Stage 2 (run): only a JRE (no compiler), the jar, the OpenTelemetry agent and the entry script. This keeps the image small.
FROM eclipse-temurin:21-jre
ARG SERVICE
# The agent is downloaded from Maven Central at build time; change the version here to upgrade it.
ARG OTEL_AGENT_VERSION=2.33.1
ADD https://repo1.maven.org/maven2/io/opentelemetry/javaagent/opentelemetry-javaagent/${OTEL_AGENT_VERSION}/opentelemetry-javaagent-${OTEL_AGENT_VERSION}.jar /opt/otel/opentelemetry-javaagent.jar
# Take only the jar out of stage 1. The "-0.0.1-SNAPSHOT" part is the version set in the root build.gradle.
COPY --from=build /src/services/${SERVICE}/build/libs/${SERVICE}-0.0.1-SNAPSHOT.jar /app/app.jar
COPY infra/docker/entrypoint-java.sh /app/entrypoint.sh
# chmod sets file permissions. `useradd --system --uid 10001 app` creates a user without a login shell or home, so the service does
# not run as root: a flaw in the service cannot then change the container's files.
RUN chmod 0644 /opt/otel/opentelemetry-javaagent.jar && chmod 0755 /app/entrypoint.sh && useradd --system --uid 10001 app
# From here on, and when the container runs, the user is "app".
USER app
# The container runs the script, which starts Java (JSON array form: no shell parsing, so the signal reaches the script).
ENTRYPOINT ["/app/entrypoint.sh"]
