// OpenTelemetry traces to OpenObserve. This module is imported FIRST by server.js, before anything loads node:http, so the
// instrumentation
// can patch it. It does nothing unless OTEL_EXPORTER_OTLP_ENDPOINT is set (the local `npm start` and the tests send nothing).
//
// The incoming request becomes a span, and every call the Gateway makes to a service (fetch) is a child span that carries the W3C
// traceparent header, so one trace follows a request from the browser through the Gateway into the Spring service.
import { monitorEventLoopDelay } from 'node:perf_hooks';
import { getHeapStatistics } from 'node:v8';
import { metrics } from '@opentelemetry/api';
import { OTLPMetricExporter } from '@opentelemetry/exporter-metrics-otlp-http';
import { OTLPTraceExporter } from '@opentelemetry/exporter-trace-otlp-http';
import { HttpInstrumentation } from '@opentelemetry/instrumentation-http';
import { UndiciInstrumentation } from '@opentelemetry/instrumentation-undici';
import { PeriodicExportingMetricReader } from '@opentelemetry/sdk-metrics';
import { NodeSDK } from '@opentelemetry/sdk-node';
import { BatchSpanProcessor } from '@opentelemetry/sdk-trace';

// Span attributes that can hold a full URL, and so a query string.
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

// NodeSDK is the OpenTelemetry starter. HttpInstrumentation traces incoming requests; UndiciInstrumentation traces the fetch calls
// (undici is
// the library behind Node's fetch) and adds the traceparent header so Spring services continue the same trace. BatchSpanProcessor
// exports in batches.
if (process.env.OTEL_EXPORTER_OTLP_ENDPOINT) {
  // Basic auth for OpenObserve, set on the exporter itself (no header string to encode in an environment variable).
  const headers = process.env.OPENOBSERVE_USER
    ? { Authorization: `Basic ${Buffer.from(`${process.env.OPENOBSERVE_USER}:${process.env.OPENOBSERVE_PASSWORD ?? ''}`).toString('base64')}` }
    : undefined;
  const sdk = new NodeSDK({
    serviceName: process.env.OTEL_SERVICE_NAME ?? 'gateway',
    // Saturation metrics, exported every 30 s (OTEL_METRIC_EXPORT_INTERVAL overrides, in ms).
    metricReaders: [
      new PeriodicExportingMetricReader({
        exporter: new OTLPMetricExporter({ headers }),
        exportIntervalMillis: Number(process.env.OTEL_METRIC_EXPORT_INTERVAL ?? 30000),
      }),
    ],
    spanProcessors: [queryStringRedactor, new BatchSpanProcessor({ exporter: new OTLPTraceExporter({ headers }) })],
    instrumentations: [
      new HttpInstrumentation({ ignoreIncomingRequestHook: (req) => req.url === '/health' }),
      // The log shipper's own calls to OpenObserve are not traced (they would only trace themselves).
      new UndiciInstrumentation({ ignoreRequestHook: (request) => request.path.startsWith('/api/default/') }),
    ],
  });
  sdk.start();
  registerSaturationMetrics();
  process.on('SIGTERM', () => void sdk.shutdown());
}

/**
 * The Gateway's saturation signals as observable gauges (read once per export): memory, CPU and event-loop delay. The event-loop delay
 * is the p99 of the delay histogram of the last interval, in milliseconds, and the histogram is reset after each read.
 */
function registerSaturationMetrics() {
  const meter = metrics.getMeter('gateway');
  const loop = monitorEventLoopDelay({ resolution: 20 });
  loop.enable();
  let lastCpu = process.cpuUsage();
  let lastAt = process.hrtime.bigint();

  meter.createObservableGauge('process.memory.rss', { unit: 'By' }).addCallback((r) => r.observe(process.memoryUsage().rss));
  meter.createObservableGauge('nodejs.heap.used', { unit: 'By' }).addCallback((r) => r.observe(process.memoryUsage().heapUsed));
  meter.createObservableGauge('nodejs.heap.limit', { unit: 'By' }).addCallback((r) => r.observe(getHeapStatistics().heap_size_limit));
  // CPU as a fraction of one core since the previous read.
  meter.createObservableGauge('process.cpu.utilization', { unit: '1' }).addCallback((r) => {
    const now = process.hrtime.bigint();
    const cpu = process.cpuUsage(lastCpu);
    const elapsedMicros = Number(now - lastAt) / 1000;
    if (elapsedMicros > 0) r.observe((cpu.user + cpu.system) / elapsedMicros);
    lastCpu = process.cpuUsage();
    lastAt = now;
  });
  meter.createObservableGauge('nodejs.eventloop.delay.p99', { unit: 'ms' }).addCallback((r) => {
    r.observe(loop.percentile(99) / 1e6);
    loop.reset();
  });
}
