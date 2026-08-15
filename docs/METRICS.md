# Metrics Contract

This document is the **interface** between the outbox-gateway microservice and the central
observability stack (Prometheus/Grafana/Alertmanager, maintained outside this repository).

The gateway does not ship dashboards or alert rules. It exposes a stable, documented metric
surface, and this document is the contract the central stack builds against.

> **Change policy:** any change that renames, removes, or alters the labels of a metric below
> MUST update this document **and** `PrometheusScrapeContractTest` in the same change. That test
> scrapes the real `/actuator/prometheus` endpoint and fails if the surface drifts.

## How to consume

- **Endpoint:** `GET http://<host>:${ACTUATOR_PORT}/actuator/prometheus` — separate management
  port, default `1000` (see [ENVIRONMENT_VARIABLES.md](./ENVIRONMENT_VARIABLES.md)).
- **Environment label:** every metric carries `env="<METRICS_ENV>"` (default `local`) so a
  shared Prometheus can distinguish environments.
- **Scrape interval recommendation:** 15–30 s. DLQ gauges poll the DB every
  `GATEWAY_METRICS_DLQ_POLL_INTERVAL_MS` (default 30000 ms), so faster scraping adds nothing.

## Cardinality rules (read before writing any alert or panel)

1. **Never group or filter by `callerService`.** It is header-controlled (`X-Caller-Service`),
   normalized (regex + 64-char cap) but unbounded in set size. The gateway caps it at the
   producer side: past 100 distinct values, new ones stop being recorded
   (`MeterFilter.maximumAllowableTags` with deny). It exists for debugging only.
2. **Never group by `id` on `reactor_netty_*` metrics.** It is a hash code, unstable across
   restarts. Group pool metrics by `name` (the base URL) or `remote.address`.
3. **The `uri` label on `reactor_netty_http_client_*` metrics is bounded by the gateway** to
   the first path segment (e.g. `/v1`); `UNKNOWN` for non-path URIs. Grouping by `uri` is
   safe, but per-system detail already lives on `api_public_seconds` via `feature`.
4. **Resilience4j and pool series are lazy.** They appear only after a system's first call
   (instances are created on demand). A system with no traffic has no series — that is
   expected, not a data gap.

## Metric families

### Gateway calls

| Prometheus name | Type | Labels | Semantics |
|---|---|---|---|
| `api_public_seconds` | histogram | `feature`, `host`, `responseCode`, `callerService`, `error`, `env` | Every outbound gateway call — latency, count (`_count`), sum (`_sum`), and histogram buckets (`_bucket`) |

- `feature` — outbound system code (e.g. `xendit-charge`). **The primary grouping label.**
- `host` — the configured `base_url` of the system's host configuration. Bounded (UNIQUE
  constraint per host). Join key to pool metrics.
- `responseCode` — actual upstream HTTP status, or `429`/`502`/`503`/`504` for internal
  rejects (circuit breaker, bulkhead, rate limiter, timeout).
- `error` — observation error value, `none` for success.
- Percentiles are computed via `histogram_quantile` — buckets are enabled (percentiles-histogram)
  **only for this metric** to keep cardinality bounded.

**Answers:** "is system X healthy?", "why did calls to partner Y fail overnight?" (error rate
per `feature` over a window, calls by `responseCode` stacked).

**Suggested alerting uses:**
- Error rate per `feature`: `sum(rate(api_public_seconds_count{responseCode=~"4..|5.."}[5m])) by (feature) / sum(rate(api_public_seconds_count[5m])) by (feature)`
- p95 per `feature`: `histogram_quantile(0.95, sum(rate(api_public_seconds_bucket[5m])) by (feature, le))`

### Idempotency

| Prometheus name | Type | Labels | Semantics |
|---|---|---|---|
| `api_public_idempotency_total` | counter | `feature`, `env` | Incremented when a call is served from the idempotency cache |

**Answers:** "how much duplicate-call traffic is each system absorbing?"

### Dead-letter queue

| Prometheus name | Type | Labels | Semantics |
|---|---|---|---|
| `outbox_dlq_depth` | gauge | `process_type`, `process_name`, `env` | Unprocessed dead-letter rows, per retry process group. Gauges appear/disappear with data — absence of the series means depth 0. |
| `outbox_dlq_oldest_ts_seconds` | gauge | `process_type`, `process_name`, `env` | Epoch seconds (UTC) of the oldest unprocessed row. Compute staleness as `time() - outbox_dlq_oldest_ts_seconds` — the value does not drift between polls. |

**Answers:** "how deep is the DLQ?", "is there a message nobody has retried in days?"

**Suggested alerting uses:** depth above threshold per `process_type`; staleness above 24 h.
Note DLQ retry is currently manual (internal API) — a depth alert is an operator action item.

### Connection pool (per host)

| Prometheus name | Type | Labels | Semantics |
|---|---|---|---|
| `reactor_netty_connection_provider_total_connections` | gauge | `name`, `remote.address`, `id`, `env` | Total connections in the host's pool |
| `reactor_netty_connection_provider_active_connections` | gauge | same | Connections currently acquired |
| `reactor_netty_connection_provider_idle_connections` | gauge | same | Idle keep-alive connections |
| `reactor_netty_connection_provider_max_connections` | gauge | same | Configured pool maximum |
| `reactor_netty_connection_provider_pending_connections` | gauge | same | Waiting acquire requests |
| `reactor_netty_connection_provider_max_pending_connections` | gauge | same | Configured pending-acquire maximum |
| `reactor_netty_connection_provider_pending_connections_time_seconds` | timer | same + `status` | Time spent waiting for a connection (`SUCCESS`/`ERROR`) |

`name` is the pool name = the `base_url` of `outbound_host_configurations` — the same value as
the `host` label on `api_public_seconds`, so the two join directly.

**Answers:** "are we near pool limits for host X?" (`active` near `max`, or `pending` > 0
sustained).

### HTTP client (per host)

| Prometheus name | Type | Labels | Semantics |
|---|---|---|---|
| `reactor_netty_http_client_data_received_bytes_total` | counter | `remote.address`, `uri`, `status`, `method` | Bytes received from upstream |
| `reactor_netty_http_client_data_sent_bytes_total` | counter | same | Bytes sent to upstream |
| `reactor_netty_http_client_errors_total` | counter | same | Upstream connection errors |
| `reactor_netty_http_client_connect_time_seconds` | timer | same | TCP connect duration |
| `reactor_netty_http_client_tls_handshake_time_seconds` | timer | same | TLS handshake duration |

The host is identified by `remote.address` — these families carry no `name` label. The `uri`
label is bounded by the gateway to the first path segment (see cardinality rules).

### Event loop / allocator (process-wide)

`reactor_netty_eventloop_pending_tasks`, `reactor_netty_bytebuf_allocator_*` — process-wide
Netty capacity signals; useful for saturation checks, not per-system analysis.

### Resilience4j (existing, per system)

```
resilience4j_circuitbreaker_state{name="<code>"}                  # 0/1 per state
resilience4j_circuitbreaker_failure_rate{name="<code>"}
resilience4j_circuitbreaker_calls_total{name="<code>", kind="..."}
resilience4j_bulkhead_available_concurrent_calls{name="<code>"}
resilience4j_ratelimiter_available_permissions{name="<code>"}
resilience4j_retry_calls{name="<code>", kind="..."}
```

`name` is the outbound system code (= `feature` on `api_public_seconds`).

**Suggested alerting uses:** circuit breaker open for > 5 min; bulkhead fully saturated;
retry failures (`failed_with_retry`, `failed_without_retry`).

### Runtime (standard)

JVM, process, HTTP-server, and R2DBC-pool metrics are exposed by Boot actuator defaults —
consumed by the central stack's standard service panels, not documented here.

## Known limitations

- **No metrics for a zero-traffic system** — lazy registration (see cardinality rules).
- **R2DBC connection pool metrics are not exposed** — r2dbc-pool has no micrometer bridge;
  DB-pool health is visible via actuator health checks only.
- **Pool metric `id` labels are unstable** — use `name`/`remote.address`.
