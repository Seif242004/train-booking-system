// OpenTelemetry traces to OpenObserve. This module is imported FIRST by server.js, before anything loads node:http, so the instrumentation
// can patch it. It does nothing unless OTEL_EXPORTER_OTLP_ENDPOINT is set (the local `npm start` and the tests send nothing).
//
// The incoming request becomes a span, and every call the Gateway makes to a service (fetch) is a child span that carries the W3C
// traceparent header, so one trace follows a request from the browser through the Gateway into the Spring service.
import { OTLPTraceExporter } from '@opentelemetry/exporter-trace-otlp-http';
import { HttpInstrumentation } from '@opentelemetry/instrumentation-http';
import { UndiciInstrumentation } from '@opentelemetry/instrumentation-undici';
import { NodeSDK } from '@opentelemetry/sdk-node';
import { BatchSpanProcessor } from '@opentelemetry/sdk-trace';

const URL_ATTRIBUTES = ['url.full', 'http.url', 'http.target', 'url.query'];

/**
 * The query string never leaves the Gateway, not even in a span: the instrumentation records the full URL, so the attributes that hold
 * it are cut at the "?" the moment the span starts (before it can be exported).
 */
export const queryStringRedactor = {
  onStart(span) {
    for (const key of URL_ATTRIBUTES) {
      const value = span.attributes?.[key];
      if (typeof value === 'string' && (key === 'url.query' || value.includes('?'))) {
        span.setAttribute(key, key === 'url.query' ? '' : value.split('?')[0]);
      }
    }
  },
  onEnd() {},
  shutdown: async () => {},
  forceFlush: async () => {},
};

if (process.env.OTEL_EXPORTER_OTLP_ENDPOINT) {
  // Basic auth for OpenObserve, set on the exporter itself (no header string to encode in an environment variable).
  const headers = process.env.OPENOBSERVE_USER
    ? { Authorization: `Basic ${Buffer.from(`${process.env.OPENOBSERVE_USER}:${process.env.OPENOBSERVE_PASSWORD ?? ''}`).toString('base64')}` }
    : undefined;
  const sdk = new NodeSDK({
    serviceName: process.env.OTEL_SERVICE_NAME ?? 'gateway',
    spanProcessors: [queryStringRedactor, new BatchSpanProcessor({ exporter: new OTLPTraceExporter({ headers }) })],
    instrumentations: [
      new HttpInstrumentation({ ignoreIncomingRequestHook: (req) => req.url === '/health' }),
      // The log shipper's own calls to OpenObserve are not traced (they would only trace themselves).
      new UndiciInstrumentation({ ignoreRequestHook: (request) => request.path.startsWith('/api/default/') }),
    ],
  });
  sdk.start();
  process.on('SIGTERM', () => void sdk.shutdown());
}
