# ADR-002: Generic Code Namespace

## Status

Accepted

## Context

The project has an internal codename (LexaVault) but the market/product name has not been decided. We need a naming strategy that avoids costly renames later.

## Decision

Use generic, descriptive code namespaces (`com.securedoc.platform`) and domain class names (Document, VaultEntry, ReviewTask) rather than brand-specific names.

## Rationale

1. **Brand independence** — Market name can change without code impact
2. **White-label friendly** — Same codebase can serve multiple product brands
3. **Descriptive names** — Class names describe function, not marketing
4. **No rename debt** — Avoids mass refactoring when product name is finalized

## Naming Convention

| Scope | Convention | Example |
|-------|-----------|---------|
| Java packages | `com.securedoc.platform.<module>` | `com.securedoc.platform.domain` |
| Maven artifacts | `securedoc-<module>` | `securedoc-domain` |
| Domain classes | Functional/descriptive | `DocumentPackage`, `ReviewTask` |
| Repository name | `secure-document-platform` | — |

## Consequences

- "LexaVault" appears only in documentation, not in code
- Rebranding requires updating docs, UI labels, and deployment names — not packages/classes
- New team members may not immediately connect codebase name to project codename

## Alternatives Considered

- **Use codename in code**: Would require mass rename when market name decided
- **Use market name in code**: Market name not yet available
- **Abstract naming (e.g., "platform-core")**: Too generic, loses domain context
