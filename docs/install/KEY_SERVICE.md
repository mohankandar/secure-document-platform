# Key service: install guide for IT (LexaVault)

LexaVault encrypts every document file it stores (originals, redacted and split copies, extracted text, findings, page
batches) with your own keys (task 0060). The keys stay in your key service, HashiCorp Vault (Transit engine); LexaVault never
sees your wrapping key. Platform-core's `docs/install/crypto.md` has the shared part (Vault set-up, the three policies,
rotation). **Without a key service LexaVault does not start**; the only exception is below (local development).

## What to set up

1. Vault: Transit, AppRole and the policies `platform-crypto-app`, `platform-crypto-setup` and `platform-crypto-offboard`, as
   platform-core's guide says. LexaVault's own login gets `platform-crypto-app` only.
2. Setup creates your customer's two keys (`docs/install/SETUP.md`; `"customerKeys": "required"` in
   `install/product-roles.json`, so setup refuses to run without the key service).
3. LexaVault's settings:

| Setting (environment) | |
|---|---|
| `PLATFORM_CRYPTO_VAULT_ADDRESS` | `https://vault.internal:8200` |
| `PLATFORM_CRYPTO_VAULT_LOGIN` | `APPROLE` (default), `TOKEN_FILE` or `TOKEN` (local runs only) |
| `PLATFORM_CRYPTO_VAULT_ROLEID`, `PLATFORM_CRYPTO_VAULT_SECRETIDFILE` (or `_SECRETID`) | AppRole login |
| `PLATFORM_CRYPTO_VAULT_TOKENFILE` / `PLATFORM_CRYPTO_VAULT_TOKEN` | the other logins |
| `PLATFORM_CRYPTO_VAULT_NAMESPACE`, `_TRANSITMOUNT`, `_KEYPREFIX`, `_TIMEOUT` | optional |

The customer is the one setup wrote (`platform.licensing.tenant-id`). `platform.crypto.enabled=false` does not switch
encryption off: the app then does not start.

## What is encrypted, and what is not

- Encrypted: everything LexaVault stores in its document storage. How a file is protected is recorded in the database
  (`stored_files`), never read from the file: a file without a record, or one that is not exactly what was written, is not
  opened (LV-SYS-012).
- Files stored before this release stay plain and readable; the schema step gives each its record once. They leave with
  their retention. **Stop every server before that schema step**: a server of the release before would store files without
  a record, and those are not opened.
- Sent to a destination: the plain PDF, as before. A destination that is a folder holds plain PDFs (they are the customer's).
- Short-lived plain files while a document is worked on (temporary folder of the server): uploads, page images for text
  recognition, PDF work files. They are removed right after use, as before. Use an encrypted disk for that folder.
- Sensitive case field values in the database, with the same keys (`CASE_FIELDS.md`, task 0061).
- The rest of the database (case fields that are not sensitive, findings without values, audit) and the scanner bridge's own workstation folder are not
  covered by these keys.

## When the key service is down or refuses

LexaVault keeps running. Support, Status shows `Key service` with the `CRYPTO_...` code; IT is notified (critical). A step
that needs a key fails with **LV-SYS-010** (down, sealed) or **LV-SYS-011** (refused, keys missing or off). Nothing is lost:
intake is refused and can be sent again, a case that was being worked on waits under Support and is retried there, an export
waits for IT. The overall `/actuator/health` is DOWN meanwhile.

## Local development

A local start (`Start-LexaVault-Local`, profile `local`, security mode LOCAL) of a **development build** without
`PLATFORM_CRYPTO_VAULT_ADDRESS` stores plain files and says so in the log (`DEVELOPMENT_ONLY_PLAIN_FILES`). A production
build never does: it refuses to start. With the address set, a local start encrypts too. What was encrypted stays closed in
the plain mode. The local OIDC start (`Start-LexaVault-OIDC.ps1`) always needs a key service.

## Rotation

Rotate the wrapping key any time (platform-core's guide); older files still open. LexaVault does not rewrap stored files
(they leave with their retention), so keep older key versions usable for at least as long as documents are kept.

## Customer exit

`docs/install/OFFBOARDING.md`.
