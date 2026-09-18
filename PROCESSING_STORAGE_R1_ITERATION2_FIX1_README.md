# LexaVault Processing Storage R1 — Iteration 2 FIX1

Status: **VERIFIED / FROZEN**

## Why FIX1 exists

Iteration 2 runtime validation passed the intended Platform Operations and shared-filesystem flows, then exposed one HA topology edge: a `LOCAL_FILESYSTEM` provider activated while only one application node was active could remain globally READY if additional nodes later joined. On the single test laptop every process could physically see the same `C:\...` path, but on separate customer servers those identical path strings would represent different disks.

FIX1 makes this state fail closed even when the filesystem probes themselves are healthy.

## Runtime evidence already passed

- Single-node LOCAL bootstrap/readiness, normal upload/process/export, managed provider identity, cycle pinning, provider switch and historical-provider reads inside retention.
- Platform Operations UI provider create/test/activate/retire surface.
- Tenant override apply and clear/use-default.
- Multi-node protection prevented activation of a new `LOCAL_FILESYSTEM` provider once multiple nodes were active.
- Four active nodes validated and activated a `SHARED_FILESYSTEM` provider.
- New ORIGINAL / EXTRACTED_TEXT / REDACTED artifacts persisted to the shared provider and the processing-cycle pin referenced the same provider.
- Controlled node loss aged the cluster from 4 to 3 active nodes; remaining 3/3 stayed healthy and Processing Storage stayed READY.
- Shared-root outage changed readiness to NOT READY with 0/3 nodes healthy.
- Manual upload failed with `Shared processing storage root is unavailable`, processing did not start, and no artifact appeared in previous local provider roots, proving no silent local fallback.

## FIX1 changes

1. **Global readiness topology gate**
   - If more than one application node is active, every assigned processing-storage provider (deployment default or tenant override) must be `SHARED_FILESYSTEM`.
   - An already-assigned `LOCAL_FILESYSTEM` therefore makes the platform NOT READY as soon as the second node becomes active.

2. **Per-node dispatch gate**
   - `ProcessingStorageNodeReadiness` rejects an effective `LOCAL_FILESYSTEM` provider whenever active-node count is greater than one.
   - Background package processing is therefore not dispatched even if that node's local path probe is healthy.

3. **Storage-write defense in depth**
   - `LocalFileStorageAdapter` blocks new writes to `LOCAL_FILESYSTEM` while multiple nodes are active.
   - This prevents manual/API/scanner/intake writes from creating an ORIGINAL on node-local disk before the processing dispatcher has a chance to reject work.
   - Historical reads are not blocked; existing artifacts remain retrievable from their recorded provider.

4. **Operator messaging**
   - Platform Operations explicitly explains that an active local provider is not cluster-safe and that a validated `SHARED_FILESYSTEM` provider must be activated.
   - Platform health reports the same reason rather than implying only node-probe health is missing.

## Final focused validation — PASS

The FIX1 topology regression passed on 2026-09-17:

1. With one active node and a validated `LOCAL_FILESYSTEM` provider, Processing Storage reported **READY**.
2. After a second application node joined, Platform Operations reported **NOT READY** even though both nodes showed healthy filesystem probes.
3. Manual upload was blocked with `LOCAL_FILESYSTEM processing storage is not permitted while multiple application nodes are active; configure SHARED_FILESYSTEM`, and no local processing artifact was created.
4. Activating the previously validated `SHARED_FILESYSTEM` provider restored **READY** with 2/2 active nodes healthy.
5. Document upload then succeeded.

Combined with the previously passed single-node, provider-switch, tenant-override, historical-provider-read, four-node shared-storage, node-loss, shared-root outage and no-local-fallback evidence, Processing Storage R1 / Iteration 2 is **VERIFIED / FROZEN**.

## Scope unchanged

- Liquibase remains **68 changeSets**.
- SDV/master redacted delivery is unchanged.
- Derived-document delivery is unchanged.
- Retention semantics are unchanged.
- Filesystem authentication remains OS/service-account based; LexaVault stores no SMB/NFS passwords.
