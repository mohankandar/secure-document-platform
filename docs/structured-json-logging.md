# Structured JSON Logging — SaaS / Production

**Status:** IN VALIDATION — final hardening follow-up  
**Date:** 2026-09-06

## Purpose

Provide privacy-safe, machine-parseable operational logs for SaaS, on-prem, and local/demo environments while preserving an explicit readable developer override and the durable structured incident model.

## Default behavior

JSON is the default logging format everywhere, including LOCAL/demo.

- Base/production default: `classpath:logback-json.xml`
- Local default: `classpath:logback-json.xml`
- Readable local override: `classpath:logback-local.xml`
- Optional local/on-prem rolling JSON file: `classpath:logback-json-rolling.xml`
- Environment field: `securedoc.logging.environment` / `SECUREDOC_ENVIRONMENT`
- No database migration; Liquibase remains at 52 changesets.

### Local startup

Default JSON:

```powershell
.\Start-LexaVault-Local.ps1
```

Readable override:

```powershell
.\Start-LexaVault-Local.ps1 -LoggingFormat Readable
```

Change the application log level:

```powershell
.\Start-LexaVault-Local.ps1 -LogLevel INFO
```

Enable JSON console plus bounded rotating JSON files:

```powershell
.\Start-LexaVault-Local.ps1 -EnableRollingFileLog
```

`start-local.cmd` forwards the same arguments.

## Log levels

Operational levels are externally configurable without rebuilding:

- `SECUREDOC_LOG_LEVEL_ROOT` — default `INFO`
- `SECUREDOC_LOG_LEVEL_APP` — default `INFO` in base/production, `DEBUG` in LOCAL
- `SECUREDOC_LOG_LEVEL_SPRING` — default `WARN`

The local launcher exposes the application level through `-LogLevel`:

`TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`.

Production/SaaS should normally remain at `INFO`; use DEBUG/TRACE only for bounded troubleshooting.

## Console vs rolling-file retention

### SaaS / container

Use JSON console logging and let the centralized collector/platform own retention, indexing, archival, and deletion. The application does not create a second production log-retention database.

### Local / on-prem optional rolling file

`logback-json-rolling.xml` writes JSON to stdout and to `logs/securedoc-platform.json`.

Default rollover policy:

- roll daily and when the active file reaches `100MB`
- retain at most `14` history periods
- total history cap `2GB`
- gzip rolled files
- clean expired history on startup

Override with:

- `SECUREDOC_LOG_DIR`
- `SECUREDOC_LOG_MAX_FILE_SIZE`
- `SECUREDOC_LOG_MAX_HISTORY`
- `SECUREDOC_LOG_TOTAL_SIZE_CAP`

Operational log retention is separate from document/original retention.

## Correlation

`RequestCorrelationFilter` establishes exactly one UUID correlation ID per HTTP request:

- preserves a valid incoming `X-Correlation-Id`
- replaces invalid/missing input
- returns the effective value in the response header
- stores it as a request attribute so controllers/services resolve the same value

The privacy-safe MDC snapshot is propagated through configured Spring task executors and the AI provider worker pools.

## Privacy-safe MDC contract

Only explicit operational keys are accepted. Current allow-list:

`tenantId`, `actorId`, `correlationId`, `packageId`, `documentId`, `incidentId`, `eventName`, `stage`, `component`, `failureCategory`, `reasonCode`, `provider`, `model`, `attempt`, `maxAttempts`, `retryable`, `durationMs`, `status`, `decision`, `scope`, `businessOutcome`, `pageCount`, `batchCount`, `providerCalls`, `pageRetries`, `findingCount`.

Never add raw prompt/provider response/OCR/document text, matched sensitive values, protected values, credentials, tokens, or arbitrary customer content to MDC.

## Structured incident integration

Processing incident lifecycle logging includes the durable `incidentId` and safe status/reason information. Business completion remains independent from technical resolution. JSON logs are operational evidence; `processing_incidents` and `processing_incident_events` remain the durable support record.

## Startup banner

The local launcher suppresses the Spring Boot banner in JSON mode so Logback-managed output starts cleanly. A pre-context third-party warning can still occur before Logback initializes; centralized collectors should tolerate or route pre-context stderr separately.

## Final validation gate

Before changing status to VERIFIED / FROZEN after this hardening pass:

1. `mvn clean verify`
2. frontend build + auth tests
3. confirm Liquibase remains 52
4. verify default local startup emits JSON
5. verify `-LoggingFormat Readable`
6. verify `-LogLevel INFO` (or another valid level)
7. verify optional rolling file creation/rollover configuration
8. validate correlation/privacy behavior remains unchanged
9. targeted independent verification only if this hardening materially warrants it
