# Outbound Parameters

Store key/value pairs for an outbound **host**; a client references them by key and the gateway injects the stored value into the outbound call — header, query param, or JSON body. The client never sees the value and never chooses the location.

Example: `partner-id → P-123` is stored for host `https://api.partner.com` with location `HEADER` and target `X-Partner-Id`. A client calls any system on that host with `"injectKeys": ["partner-id"]` and the outbound request carries `X-Partner-Id: P-123`.

## Data model

Table `outbound_parameters` (migration `V11__create_outbound_parameters_table.sql`):

| Column | Type | Description |
|---|---|---|
| `host_configuration_id` | bigint FK | Owning host (`outbound_host_configurations.id`) |
| `param_key` | varchar(100) | Key the client sends. Unique per host |
| `param_value` | bytea | Value, AES-256-GCM encrypted |
| `inject_location` | varchar(20) | `HEADER`, `QUERY_PARAM`, `BODY` |
| `target_name` | varchar(255) | Header name / query param name / dot path in the JSON body |
| `is_active` | boolean | Inactive keys are rejected at call time |

Plus the standard audit columns (`created_by`, `updated_by`, `created_date`, `updated_date`, `version`).

## Admin API

Base path `/api/outbound/host/{id}/parameters` (see README → *Host Configuration API* for the full spec).

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/outbound/host/{id}/parameters` | Create or update (by `key`) one or more parameters |
| `GET` | `/api/outbound/host/{id}/parameters` | List parameters — **values are never returned** |
| `DELETE` | `/api/outbound/host/{id}/parameters/{key}` | Delete a parameter |

```http
POST /api/outbound/host/1/parameters
Content-Type: application/json

{
  "parameters": [
    { "key": "partner-id", "value": "P-123", "location": "HEADER",      "targetName": "X-Partner-Id" },
    { "key": "region",     "value": "ID",    "location": "QUERY_PARAM", "targetName": "region" },
    { "key": "merchant",   "value": "M-9",   "location": "BODY",        "targetName": "meta.merchantId" }
  ]
}
```

## Gateway usage

`POST /api/gateway/execute/{code}` accepts an optional `injectKeys` array:

```json
{
  "body": { "amount": 100000 },
  "injectKeys": ["partner-id", "region", "merchant"]
}
```

Outbound request produced (for the three parameters above):

```
POST /v2/charges?region=ID
X-Partner-Id: P-123

{"amount":100000,"meta":{"merchantId":"M-9"}}
```

## Location semantics

| Location | `targetName` | Behaviour |
|---|---|---|
| `HEADER` | Header name (RFC 7230 token) | Set on the outbound request, replacing a static header of the same name. The auth header is applied afterwards and always wins |
| `QUERY_PARAM` | Query param name | Added to the outbound query string; the value is URL-encoded as a single value (`&`, `+`, `{}` are not interpreted) |
| `BODY` | Dot path, e.g. `meta.merchantId` | The request `body` must be a JSON **object**. Missing intermediate objects are created; an existing value at the path is overwritten; an intermediate that is not an object → rejected. Array indexes are not supported |

`BODY` values are injected **before** the body is serialised and signed, so HMAC / RSA signatures cover them. The history row (`outbound_histories.request_payload`) stores the original payload, not the injected one.

## Rules and errors

All failures are HTTP **400** and no outbound call is made.

| Situation | `violations` |
|---|---|
| A key in `injectKeys` does not exist on the system's host, is inactive, or belongs to another host | `injectKeys: ["NotFound"]` |
| `BODY` parameter but the request has no body, or the body is not a JSON object / path conflicts | `injectKeys: ["NotValid"]` |
| Parameter targets the system's auth `tokenKeyName`, or more than 50 distinct `injectKeys` are sent | `injectKeys: ["NotValid"]` |
| Admin upsert: protected or malformed header name, malformed query name, malformed body path, duplicate key in the request | `targetName: ["NotValid"]` / `parameters: ["NotValid"]` |
| Admin: unknown host id | `id: ["NotFound"]` |

Protected headers that can never be targeted: `Authorization`, `Proxy-Authorization`, `Cookie`, `Host`, `Content-Length`, `Content-Type`, `Transfer-Encoding`, `Connection`.

## Operational notes

- **Cache.** Parameters are read through a Caffeine cache (`apps.cache.configurations.parameters`, default TTL 60 s, env `PARAMETERS_CACHE_*`). Nothing is preloaded: a key is loaded from the database on the first call that references it, then served from memory. Entries hold the value still **encrypted** (decrypted per call). Unknown / inactive keys are not cached, so a newly created key works immediately. Calls without `injectKeys` never touch the cache or the database.
  - Admin upsert/delete evicts the key on the instance that handled the request, so that instance serves the change at once.
  - **Other instances** (and a load racing an eviction) can keep serving the old value for up to the TTL — e.g. a deleted or deactivated key can still be injected for up to 60 s elsewhere. Lower the TTL, or set `PARAMETERS_CACHE_TIME_OUT=0` to disable caching, if you need faster convergence.
- **Scope is the host.** Every outbound system on the host can use the host's keys. If two systems on one host need different values for the same purpose, use different keys.
- **Logging.** Outbound request logs redact header names and JSON body keys listed in `SENSITIVE_FIELDS` (see `docs/ENVIRONMENT_VARIABLES.md`); add the names you use for secrets. Query-string values are not redacted, so avoid `QUERY_PARAM` for secrets.
- **Admin request logging.** The raw body of every request is stored by the event-log mechanism (`event_logs.payload`), so the `value` sent to `POST .../parameters` is currently persisted in clear text there. Restrict access to `event_logs` until this is addressed.
- **Header values** must not contain control characters (CR/LF/NUL/DEL); the upsert is rejected with 400.
- **Backslashes.** The outbound body passes through `GsonHelper.cleanJson`, which strips backslashes — a parameter value containing `\` is altered in `BODY` injections.
- **Deleting a host** that still has parameters fails on the foreign key; delete the parameters first.
