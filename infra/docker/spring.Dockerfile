# One image recipe for the five Spring Boot services. Build from the repository root:
#   docker build -f infra/docker/spring.Dockerfile --build-arg SERVICE=booking-seat .
# (docker-compose.yml does this.) The OpenTelemetry Java agent is added to every image; it sends traces and logs to OpenObserve when
# OTEL_EXPORTER_OTLP_ENDPOINT is set (see entrypoint-java.sh) and does nothing otherwise.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
COPY services services
ARG SERVICE
# The Gradle cache is a build cache mount, so rebuilding does not download everything again. Tests are run by `./gradlew test`, not here.
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon --console=plain :services:${SERVICE}:bootJar -x test

FROM eclipse-temurin:21-jre
ARG SERVICE
ARG OTEL_AGENT_VERSION=2.33.1
ADD https://repo1.maven.org/maven2/io/opentelemetry/javaagent/opentelemetry-javaagent/${OTEL_AGENT_VERSION}/opentelemetry-javaagent-${OTEL_AGENT_VERSION}.jar /opt/otel/opentelemetry-javaagent.jar
COPY --from=build /src/services/${SERVICE}/build/libs/${SERVICE}-0.0.1-SNAPSHOT.jar /app/app.jar
COPY infra/docker/entrypoint-java.sh /app/entrypoint.sh
RUN chmod 0644 /opt/otel/opentelemetry-javaagent.jar && chmod 0755 /app/entrypoint.sh && useradd --system --uid 10001 app
USER app
ENTRYPOINT ["/app/entrypoint.sh"]
