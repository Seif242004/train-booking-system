#!/bin/sh
# Starts a Spring service with the OpenTelemetry agent. The OpenObserve login comes from OPENOBSERVE_USER / OPENOBSERVE_PASSWORD (the
# same values as the OpenObserve container); they become the Basic authorization header of the OTLP exporter, URL-encoded as the header
# variable requires. Without OPENOBSERVE_USER the agent is switched off and the service just logs to the console.
set -eu
if [ -n "${OPENOBSERVE_USER:-}" ] && [ -n "${OTEL_EXPORTER_OTLP_ENDPOINT:-}" ]; then
  token=$(printf '%s:%s' "$OPENOBSERVE_USER" "${OPENOBSERVE_PASSWORD:-}" | base64 | tr -d '\n' | sed 's/+/%2B/g; s/=/%3D/g; s#/#%2F#g')
  export OTEL_EXPORTER_OTLP_HEADERS="Authorization=Basic%20${token}"
else
  export OTEL_SDK_DISABLED=true
fi
exec java -javaagent:/opt/otel/opentelemetry-javaagent.jar -jar /app/app.jar
