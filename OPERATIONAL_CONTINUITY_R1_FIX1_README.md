# Operational Continuity R1 FIX1 — Spring Controller Wiring

Status: **IMPLEMENTED FIX1 CANDIDATE / LOCAL STARTUP REVALIDATION REQUIRED**

## Runtime finding

The user's full local build passed and Liquibase successfully applied both `031-operational-continuity.xml` changesets (candidate total 73), but application startup then failed while creating `SupportIncidentController` with `No default constructor found`.

Root cause: the controller intentionally retained a 3-argument constructor for focused authorization tests and added a 4-argument production constructor for `SupportIncidentActionService`. With multiple constructors and no explicit injection constructor, Spring did not know which constructor to use and attempted default instantiation.

## FIX1

- Mark the 4-argument production constructor with Spring `@Autowired`.
- Preserve the existing 3-argument constructor for focused tests/backward-compatible direct construction.
- No lifecycle, retention, recovery-attempt, Support action, telemetry, database, API-contract, authorization, or frontend behavior changes.
- Liquibase remains **73 changeSets**; no new migration.
- Added a focused regression assertion that the 4-argument constructor remains the explicit Spring injection constructor.

## Local revalidation

1. Re-run the normal backend/full build.
2. Start the API using the normal LOCAL startup path.
3. Confirm Spring reaches `Started SecureDocumentPlatformApplication` instead of failing on `supportIncidentController`.
4. Continue Operational Continuity R1 runtime acceptance from the existing candidate README.
