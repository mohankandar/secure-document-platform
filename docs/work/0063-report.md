# Report 0063: R3, password-protected PDFs through the API only

Date: 2026-11-24
Branch: `feature/0063-r3-password-pdfs`, from the integration branch; 0062 merged in afterwards
Outcome: **DONE** (3 questions)

## What was done
- **Push API only** (`POST /api/v1/external-intakes/{packageId}/documents/{id}`): optional multipart part `password` (user or owner password, up to 127 bytes). No other way in has a password.
- **In memory only:** Spring writes every multipart part to a temporary file by default, a password too. `spring.servlet.multipart.file-size-threshold: 4KB` keeps small parts in memory. The app's character copy is cleared after use; PDFBox and the server need a short-lived string, which Java cannot clear.
- **Over HTTPS, in the body:** a password in the address or over plain HTTP is refused (`LV-INT-037`). HTTPS at a proxy counts when it sends `X-Forwarded-Proto: https`; calls from the same machine may use HTTP.
- **Decryption** (`PdfBoxDecryptionAdapter`): RC4 40/128, AES 128/256. The decrypted PDF goes through intake as any other; the encrypted file is not kept at all. The checksum of the file as sent still identifies a retry.
- **Wrong password:** `LV-INT-036`, the same words for every reason. The audit says only "decrypted with a supplied password".
- **Every other way in:** `LV-INT-008`, reworded, with "Password-protected file. Send it through the API."
- **No new dependency, no migration.** New case screen unchanged. API spec, error-code documents and user guide updated.

## Commands run and results
| Command | Result |
|---|---|
| `mvn -B -o -Pdev-build clean verify`, PostgreSQL on (throwaway), after merging 0062 | BUILD SUCCESS in an isolated copy of the commit: domain 84, application 303, scan bridge 136, infrastructure 121, API 773 (1 skipped), 0 failures. The run before it, in the worktree, had 8 API failures (`NoClassDefFoundError`, `NoSuchMethodError` for our own classes, and a 500): class files were replaced while it ran, probably by the editor; cause not traced |
| real app, no-redacted-value check extended with a locked AES-256 PDF | PASS: no password 400, wrong 400, in the address 400, right 201, retry 200; case COMPLETED; 1 audit record; 0 matches of either password (also base64) in database, logs, audit exports and answers; 0 files on disk holding one; 0 encrypted PDFs left |
| `npm run test:vocabulary` (the only frontend change is the generated code list and its count) | pass (200) |
| product-name guard | clean |

New tests: `PdfBoxDecryptionAdapterTest` (4 ciphers, both passwords, wrong ones, temporary files, logs), `PdfPasswordUploadTest` (body only, HTTPS, cleared, not repeated, in-memory setting).

The first run of the extended check stopped in the script itself (it read the mock's own open log file); fixed, the run above is the second.

## Existing tests changed
Counts of codes: `LvErrorCodeRegistryTest` (40 to 42, 141 to 143) and `lvCodes.test.ts` (141 to 143).

## Not done, or left for the milestone
- The rest of `npm run test:standalone`, two-server proof: M1 (no screen changed).
- Watched folder, polling and scanner with a locked file were not run end to end; they use the same check (`PdfBoxIntakeValidationAdapterTest`).

## Questions for the owner (recommended one taken)
1. A PDF with an owner password only (opens without one) is taken in everywhere, as today. Keep?
2. HTTPS accepted on the proxy's `X-Forwarded-Proto` word. Keep?
3. A wrong password can be tried again without a limit (the caller is an authenticated source). Keep?

## Suggested next step
Task 0066.

No other product's name or behaviour in code.
