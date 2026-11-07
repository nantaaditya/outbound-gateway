# Environment Variables

All configuration is externalized through environment variables with sensible defaults for local development.

> **WARNING — Production:** Do not rely on defaults in production. At minimum, override `GATEWAY_ENCRYPTION_KEY`, `DB_USERNAME`, and `DB_PASSWORD`.

---

## Server

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `SERVER_PORT` | No | `8080` | HTTP server port |
| `APP_NAME` | No | `outbound-gateway` | Spring application name |
| `CONTEXT_PATH` | No | `/app` | WebFlux base context path |
| `APP_VERSION` | No | `1.0.0` | Application version string |

---

## Database (R2DBC — Reactive)

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `R2DBC_URL` | No | `r2dbc:postgresql://localhost:5432/outbox-gateway` | R2DBC connection URL |
| `DB_USERNAME` | **Yes** | `user` | Database username |
| `DB_PASSWORD` | **Yes** | `password` | Database password |
| `DB_POOL_SIZE` | No | `5` | Initial connection pool size |
| `DB_MAX_POOL_SIZE` | No | `10` | Maximum connection pool size |
| `DB_IDLE_TIME` | No | `30s` | Max idle time before connection is released |
| `DB_LIFE_TIME` | No | `60S` | Max lifetime of a connection |

---

## Database (Flyway — Migrations)

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `DB_URL` | No | `jdbc:postgresql://localhost:5432/outbox-gateway` | JDBC URL for Flyway (requires JDBC driver) |
| `FLYWAY_ENABLED` | No | `true` | Enable Flyway migration on startup |
| `FLYWAY_VALIDATION_ENABLED` | No | `false` | Validate migration checksums on startup |

---

## Management / Actuator

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `ACTUATOR_PORT` | No | `1000` | Management endpoint port (separate from server port) |
| `ACTUATOR_SHUTDOWN` | No | `none` | Shutdown endpoint access (`none` or `read-only`) |
| `HEALTH_DETAIL` | No | `always` | Health endpoint detail level (`always`, `when-authorized`, `never`) |
| `ACTUATOR_EXPOSED` | No | `*` | Exposed actuator endpoints (comma-separated) |
| `METRICS_ENV` | No | `local` | `env` label applied to every metric — used by the central observability stack to distinguish environments. See [METRICS.md](./METRICS.md) |
| `GATEWAY_METRICS_DLQ_POLL_INTERVAL_MS` | No | `30000` | DLQ-depth gauge poll interval in milliseconds |
| `GATEWAY_METRICS_DLQ_INITIAL_DELAY_MS` | No | `5000` | Initial delay before the first DLQ-depth gauge poll, in milliseconds |

---

## Tracing (Micrometer / Zipkin)

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `TRACING_SAMPLING_PROBABILITY` | No | `1.0` | Trace sampling rate (0.0–1.0). Set lower in high-traffic environments |

---

## Logging

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `ROOT_LOG_LEVEL` | No | `INFO` | Root logger level |
| `APP_LOG_LEVEL` | No | `INFO` | Application package (`com.nantaaditya`) log level |
| `ZALANDO_LOG_LEVEL` | No | `TRACE` | Logbook HTTP logging level |
| `LOG_PATH` | No | `logs/` | Log file output directory |
| `APPS_LOG_FORMAT` | No | `json` | Log output format (`json` or `text`) |
| `ENABLE_TRACE_LOG` | No | `true` | Enable per-request HTTP trace logging |
| `IGNORED_TRACE_LOG_PATH` | No | `*/swagger-ui,...` | Paths excluded from trace logging (comma-separated globs) |
| `SENSITIVE_FIELDS` | No | `x-client-id,authorization,...` | Header names redacted in logs (comma-separated, case-insensitive) |
| `MAX_PAYLOAD_SIZE` | No | `1048576` | Max request body size in bytes (1 MB) before the filter rejects with 413. Reading stops at the limit; a rejected request is still written to the event log (empty payload). Property: `apps.filter.max-body-bytes` |
| `MAX_RESPONSE_BYTES` | No | `10485760` | Largest upstream response the gateway holds in memory (10 MB). A bigger answer is reported as 502 "upstream response too large" even though the upstream already processed the request. Property: `apps.gateway.max-response-bytes` |
| `IDEMPOTENCY_LEASE` | No | `120s` | How long one instance may hold an idempotency key while it calls the upstream. Must exceed the longest possible call (read timeout × retry attempts). Other instances wait up to this long for the result, then take the key over (crashed owner) or answer 409 |
| `IDEMPOTENCY_POLL_INTERVAL` | No | `200ms` | How often an instance waiting for another instance's result checks for it |

---

## Gateway Encryption

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `GATEWAY_ENCRYPTION_KEY` | **Yes** | Dev key (see warning) | Base64-encoded AES-256 key for encrypting auth secrets at rest. Generate via `EncryptionHelper`. **Must be overridden in production.** |

---

## Async Task Executor

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `DEFAULT_TASK_CORE_POOL_SIZE` | No | `5` | Core thread pool size for `@Async` tasks |
| `DEFAULT_TASK_MAX_POOL_SIZE` | No | `20` | Max thread pool size |
| `DEFAULT_TASK_QUEUE` | No | `100` | Task queue capacity before rejecting |
| `DEFAULT_TASK_KEEP_ALIVE` | No | `60` | Idle thread keep-alive in seconds |
| `DEFAULT_TASK_THREAD_NAME` | No | `default-task-` | Thread name prefix |
| `DEFAULT_TASK_VIRTUAL_THREAD_ENABLE` | No | `true` | Use virtual threads (Java 21+) for async tasks |

---

## Caches

### JSLT Template Cache

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `JSLT_CACHE_TYPE` | No | `SIZE` | Eviction policy (`SIZE` or `TIME`) |
| `JSLT_CACHE_SIZE` | No | `200` | Max cached JSLT templates |

### Context Cache

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `CONTEXTS_CACHE_TYPE` | No | `TIME` | Eviction policy |
| `CONTEXTS_CACHE_TIME_OUT` | No | `10` | TTL duration |
| `CONTEXTS_CACHE_TIME_UNIT` | No | `MINUTES` | TTL time unit (`SECONDS`, `MINUTES`, `HOURS`) |

### Additional Data Cache

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `ADDITIONAL_DATA_CACHE_TYPE` | No | `TIME` | Eviction policy |
| `ADDITIONAL_DATA_CACHE_TIME_OUT` | No | `10` | TTL duration |
| `ADDITIONAL_DATA_CACHE_TIME_UNIT` | No | `MINUTES` | TTL time unit |
| `PARAMETERS_CACHE_TYPE` | No | `TIME` | Eviction policy for outbound parameters |
| `PARAMETERS_CACHE_TIME_OUT` | No | `60` | TTL duration (`0` disables the cache) |
| `PARAMETERS_CACHE_TIME_UNIT` | No | `SECONDS` | TTL time unit |

---

## Summary

- **Total variables:** 52
- **Required (production):** `DB_USERNAME`, `DB_PASSWORD`, `GATEWAY_ENCRYPTION_KEY`
- **All others:** Optional with local-development defaults

### Quick-start (local dev)

```bash
# Minimal local setup — all defaults work against a local PostgreSQL
export DB_USERNAME=postgres
export DB_PASSWORD=postgres
export GATEWAY_ENCRYPTION_KEY=<generated-key>
mvn spring-boot:run
```
