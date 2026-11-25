# Sending to a destination

Task 0087. Finished cases go to one **destination** for each entity: a system whose owner publishes a *profile* (a file in
the format `platform-integration-profile/1`). The profile says how the destination works: its calls, its answers, its
sign-in, its limits. LexaVault ships no profile and knows no destination by name.

## 1. What IT needs from the destination's owner

1. The profile file and its release tag. Keep it unchanged.
2. The address, and how to sign in (for example a client ID and secret in the login system).
3. Which case fields it takes, and its codes for a refused sensitivity level.

## 2. Settings of the installation

| Setting | |
|---|---|
| `SECUREDOC_DELIVERY_TYPE` | `document-store` (default): to a destination. `local`: to a folder |
| `SECUREDOC_DELIVERY_PROFILE_FOLDER` | the folder with the profile files (`.json`). The app's service account reads it; nobody else should be able to write to it |
| `platform.outbound.*` | the outbound rules: proxy, private CA, whether internal addresses (and plain `http` there) are allowed, whether a call without sign-in is allowed (`allow-sign-in-none`, off) |

Every call goes through the outbound rules. A cloud metadata or link-local address is always refused; an internal address
and plain `http` unless `platform.outbound.allow-internal-addresses=true` (on premises only).

**The secret** (client secret, key, token or password) is a reference, read at each use:
`env:LEXAVAULT_SECRET_<entity id, 32 characters>_<NAME>` or `file:` with the same name (`NOTIFICATIONS.md`, "Secrets"). An
entity can name only its own secrets. References of an earlier release (`env:` with any name, `sys:`) are refused.

## 3. The connection (Admin, Integrations, "Sending to the document store")

Choose the profile, the address, the sign-in the profile takes and the secret reference. Then:

- **Case fields.** Either every case field under its own key, or by name (`their name=case field key`). A field the case
  does not hold is left out, unless the destination needs it. Numbers, amounts and dates go out typed.
- **Sensitive case fields** are sent only when their key is listed. A case with another sensitive field is not sent
  (LV-DLV-006, `INT_SENSITIVE_FIELD_NOT_ALLOWED`). With the key service down nothing is sent.
- **Sensitivity levels.** Each document goes out with its own level. When the profile has no place for the level of the
  whole case, a case above Standard is not sent (LV-DLV-003), or each document is sent with the higher of the two levels:
  IT chooses.
- **Codes that refuse a level** (for example `ABC-LEVEL-*`): shown as LV-DLV-003 instead of LV-DLV-004.

"Test connection" makes the health check and the sign-in. It never sends a document.

The connection keeps the profile's name and version. When the file in the folder is another version, nothing is sent
(LV-DLV-006, `DESTINATION_PROFILE_CHANGED`) until IT opens the connection and saves it again.

## 4. What the destination's answers become

| Answer (by the profile's rules) | LexaVault |
|---|---|
| Sent, or already received | exported; already received is LV-DLV-009 |
| Accepted, check later | waits and asks again, using no attempt |
| Busy | LV-DLV-002: waits as long as the destination asks (at most 1 hour), one worker less, no attempt used |
| Down or failing | LV-DLV-002: retry schedule; the destination is paused when its health check fails |
| Can't be reached | LV-DLV-001, as above |
| Sign-in refused, secret missing | LV-DLV-005, to IT (a refused token is fetched again once first) |
| Refused with a code | LV-DLV-004 (or LV-DLV-003), "Waiting for you"; the code is in the details |
| Answer not understood, or another checksum than the one sent | LV-DLV-007, retry schedule, then IT |
| Not set up | LV-DLV-006, to IT; the reason's code is in the details |

After every call that worked the progress is saved, so another try (also on another server) goes on from there, with the
same ids. Documents are streamed from their stored copy and never written to disk in plain.

## 5. Upgrading from an earlier release

1. Before the upgrade, let the export queue run empty (no case waiting, none half sent).
2. Stop every server, run the schema step (migration 067), start. A connection of an earlier release is kept as it is,
   switched on, but **nothing is sent with it**: cases stop at LV-DLV-006 (`DESTINATION_PROFILE_MISSING`) in IT's queue.
3. Set up section 2, open the connection, choose the profile and the new secret reference, test, save.
4. Retry the waiting cases from the IT error queue.

A case an earlier release had **half sent** (the destination already has its number, or some documents) is not continued:
LV-DLV-016. Look in the destination whether it is complete there; the code's steps say what to do.

Going back (rollback of 067) is refused while a delivery is half done.

## 6. Checking a destination's files before using them

`scripts/Test-LexaVault-DestinationContract.sh <profile> <openapi>` runs the contract kit on the profile and its
specification. Without the two files the build checks its own made-up profile.
