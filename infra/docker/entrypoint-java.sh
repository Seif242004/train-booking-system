#!/bin/sh
# Starts a Spring service with the OpenTelemetry agent. The OpenObserve login comes from OPENOBSERVE_USER / OPENOBSERVE_PASSWORD (the
# same values as the OpenObserve container); they become the Basic authorization header of the OTLP exporter, URL-encoded as the header
# variable requires. Without OPENOBSERVE_USER the agent is switched off and the service just logs to the console.
# set -e stops the script at the first failing command; -u makes an unset variable an error (the ${VAR:-} forms below give a default).
set -eu
if [ -n "${OPENOBSERVE_USER:-}" ] && [ -n "${OTEL_EXPORTER_OTLP_ENDPOINT:-}" ]; then
  # Builds the HTTP Basic credential "user:password" in Base64, then URL-encodes the three characters of Base64 that are special in a
  # header variable (+ = /). `tr -d '\n'` removes the line breaks that `base64` may add.
  token=$(printf '%s:%s' "$OPENOBSERVE_USER" "${OPENOBSERVE_PASSWORD:-}" | base64 | tr -d '\n' | sed 's/+/%2B/g; s/=/%3D/g; s#/#%2F#g')
  # The exporter sends this header with every OTLP request. "%20" is the encoded space between "Basic" and the token.
  export OTEL_EXPORTER_OTLP_HEADERS="Authorization=Basic%20${token}"
else
  # Without a login or an endpoint the agent would only print export errors, so it is switched off. The service still logs to the console.
  export OTEL_SDK_DISABLED=true
fi
# `exec` replaces this shell by the Java process, so Java becomes the container's main process and receives the stop signal (SIGTERM)
# from Docker directly, which lets Spring shut down cleanly. -javaagent attaches the OpenTelemetry agent before the application starts.
exec java -javaagent:/opt/otel/opentelemetry-javaagent.jar -jar /app/app.jar
