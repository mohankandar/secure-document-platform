# Dashboard Action Menu and Remove Confirmation — 2026-08-31

## Approved UX

For APPROVER dashboard rows, replace repeated destructive-looking `Delete Package` links with a touch-friendly row action menu:

```text
⋮
View Details
Remove from Dashboard   (COMPLETED only)
```

`Remove from Dashboard` opens a confirmation modal on desktop. On narrow/touch layouts the same component presents as a bottom-sheet style confirmation with larger touch targets.

## Semantics

- This is **not** a DB delete.
- This is **not** an immediate file delete.
- The package remains in PostgreSQL with lifecycle/audit history.
- The existing dashboard-hidden marker/audit event remains unchanged.
- Existing retention cleanup remains the only physical artifact deletion mechanism.
- The package disappears from normal Dashboard/KPI queries after confirmation.

## Tenant terminology

The confirmation dialog uses the configured primary business identifier label/value from dashboard metadata when available. It must not hardcode `Contract Number` for every tenant.

## Scope protection

This UX polish does not change AI detection, tenant AI routing, Manual Review behavior, polling behavior, retention scheduling, or backend removal semantics.
