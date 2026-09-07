LexaVault Structured JSON Logging — final hardening candidate
Date: 2026-09-06

Baseline:
- Initial Structured JSON Logging + startup selector independently verified by Kiro: PASS
- Backend 173 tests green, frontend build PASS, auth 8/8, Liquibase 52

This follow-up changes:
- JSON is the LOCAL startup default.
- `-LoggingFormat Readable` is the explicit readable override.
- `-LogLevel TRACE|DEBUG|INFO|WARN|ERROR` controls the local application log level.
- `-EnableRollingFileLog` enables JSON console + bounded rotating JSON file for local/on-prem use.
- JSON startup suppresses the Spring Boot banner.
- SaaS/container remains JSON stdout by default; centralized observability owns retention.
- No Liquibase migration.

Local commands:

Default JSON:
    .\Start-LexaVault-Local.ps1

Readable:
    .\Start-LexaVault-Local.ps1 -LoggingFormat Readable

JSON at INFO:
    .\Start-LexaVault-Local.ps1 -LogLevel INFO

JSON + rolling file:
    .\Start-LexaVault-Local.ps1 -EnableRollingFileLog

Validation status:
- IN VALIDATION until this final hardening pass is built and smoke-tested locally.
