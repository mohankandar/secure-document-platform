# Decision: integrations move to a platform-core integration module

Owner, 2026-11-21, in the LexaVault design chat, after the 0031 live run found that LexaVault's Vero sender doesn't match Vero R7 (addresses, upload headers, sign-in): every earlier test used a fake Vero built from our own `SDV_DELIVERY_V1.md`.

## Split of duties
- **platform-core: an integration module, inbound and outbound, configured not coded.**
  - Calls: method, address, header and body templates, response mapping (their reference, status, error code).
  - Field mapping by case-field key (platform-fields), e.g. `contract_number`.
  - Inbound: polling an API; picking up files from a folder with the trigger-file rules (`Watched-Folder-Trigger-File.md`).
  - Outbound: API calls; placing files in a folder or network share with a **manifest JSON written last** (common envelope plus an app section). SFTP later, on request.
  - Sign-in: **none** (internal network, on-premises only, off by default, warning, audited, shown on the compliance report; never on hosted), **shared secret** (user and password, or API key), **machine token** (JWT / OAuth2 client credentials), **mutual TLS** (certificate uploaded on the integration page and stored encrypted, never shown again, only subject, fingerprint and expiry; or a keystore on the server).
  - Common plumbing: request IDs, checksums, health check, one classified result per attempt (sent / already received / refused with code / sign-in failed / can't connect / busy).
  - Vero R7's ingestion API as a ready-made profile.
  - Anything else: a coded extension for that customer, never an app change.
  - Safety: a fixed set of building blocks; secrets only in the secret store; no content or secrets in URLs, templates or logs; protection against cloud-metadata addresses (internal addresses allowed on-premises); configuration audited and versioned.
  - Testing: a Test action with fake data, contract tests against each published API, integration tests marked to run only against a live system.
- **LexaVault keeps its workflow:** what goes in the package (redacted documents, case fields, redaction status, sensitivity levels for the manifest's app section), the export job and its rules (pause by health check, retry schedule, rebuild, Waiting for you, IT queue, screens, retention per destination; tasks 0027–0030), intake rules, and its own push API specification (caller sign-in checked with the shared bundle).
- **This reverses 0021's "no file format for folder exports":** folder exports now carry the manifest.

## Effect on the order
LexaVault moves its intake and export onto the module as **switch-over part 3**, after platform-core builds it (it builds on platform-fields). 0031 is re-run jointly when both are ready. Nothing else in the order changes.

## Catalog of options (owner-approved 2026-11-21)

V1 = build now; Later = when a customer asks; Extension = custom for one customer.

- **Intake:** push API (V1, LexaVault's own API) · polling an API (V1) · watched folder / network share with trigger file (V1) · SFTP pickup (Later, likely early) · cloud storage S3 / Azure Blob / GCS (Later) · SharePoint / OneDrive (Later) · email inbox incl. fax-to-email (Later, with spam/malware/sender checks) · message queue Kafka / IBM MQ / RabbitMQ (Extension). Scanner and manual upload stay in LexaVault.
- **Export:** API push (V1) · folder / network share with manifest JSON last (V1) · SFTP drop (Later, likely early) · cloud storage (Later) · document management systems SharePoint, Box; legal iManage, NetDocuments (Later, each a profile) · message queue notice (Extension) · email (not offered).
- **Sign-in:** none, on-premises only (V1) · shared secret: user and password or API key in a header, never in the address (V1) · fixed bearer token (V1) · OAuth2 client credentials / JWT (V1) · private-key JWT (Later) · mutual TLS, uploaded or server keystore (V1) · Windows sign-in Kerberos/NTLM (Later, common on-premises) · SSH key (with SFTP) · cloud access keys / managed identity (with cloud storage) · HMAC-signed requests for callbacks (V1).
- **Packaging in an API call:** multipart documents + JSON (V1) · two steps, create then upload, Vero's way (V1) · one file per call with details in headers (V1) · JSON with base64 documents (Later) · ZIP bundle (Later) · XML / SOAP incl. MISMO XML for mortgage (Later, likely early).
- **Behaviours (all V1):** 202 accepted then status check or callback · paging when polling · honouring 429 / Retry-After · the customer's outbound proxy · a private certificate authority · request IDs and checksums · size limits and timeouts per integration.

## Shared thread's answer (2026-11-21)
- Already in platform-core: HTTP sending with failure codes, Retry-After and timeouts (notifications), OAuth client-credentials tokens, signed expiring tokens, secrets by reference, field keys and the key + type + value delivery format (platform-fields, P017), and the requirements in `docs/design/integration-standards.md`. The rest of the catalog is planned.
- Their order: P018 encryption → P019 field values → P020 entity time zone → P021 integration investigation (incl. Vero R7's real API) → P022+ build. Recorded in platform-core `docs/design/integration-module.md` and project docs `Integration-Module-Design.md`, `Platform-Core-Status.md`.
- LexaVault: switch-over part 2 (case fields) after P019; per-entity time zone (backlog BL5) from P020; switch-over part 3 (intake and export onto the module) after P022+.

## Vero thread's answer (2026-11-21)
- **Specification:** `docs/api/sdv-ingestion-v1.openapi.json` (OpenAPI 3.1, v1.0.0) in `mohankandar/secure-document-vault`, branch `release/vero-r7`; error codes and what to do in `docs/api/error-codes.md` there. Vero task 0040 completes and enforces it (readiness, sign-in claims, retry / don't-retry per answer, versioning: `/v1` stays backward compatible, breaking changes need `/v2`, a contract test in Vero's build, a sender's guide). **Build the Vero profile against that file once 0040 is merged.**
- **Sign-in:** in `enterprise-jwt` mode Vero accepts a sending system's bearer token (e.g. OAuth2 client credentials) from the customer's login server. The token carries the configured audience, a subject, and two claims: the tenant ID and the sending system's source ID (claim names are deployment settings). Vero takes tenant and sender only from the verified token. On the login server: a client for LexaVault with those two claims. Vero 0040 checks this live with the local Keycloak.
- **Answers:** 201 received; 200 already received (same idempotency key); 200 `DUPLICATE_ALREADY_STORED` (same file on the same contract, not stored twice); refusals `SDV-INT-*`, `SDV-SEN-*`, sign-in `SDV-AUT-*` (don't retry); 429 and 503 (`SDV-SYS-014`, `SDV-AUT-004`, `SDV-STO-001`) retry later; every error body `{code, message, reference, timestamp}`. "Can I send now?": `/actuator/health/readiness`.
- **Fit with LexaVault's rules (0027–0030):** both 200 answers count as exported (`LV-DLV-009`); `SDV-INT-*` / `SDV-SEN-*` refusals go to Waiting for you (`LV-DLV-004` / `LV-DLV-003`); `SDV-AUT-*` to Needs IT (`LV-DLV-005`); 429/503 follow the evidence rule, with Vero's readiness endpoint as its health check.
