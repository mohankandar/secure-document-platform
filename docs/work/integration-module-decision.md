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
