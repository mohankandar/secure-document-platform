# Naming and Rebranding Strategy

## Current Names

| Context | Name | Purpose |
|---------|------|---------|
| Internal codename | LexaVault | Project identification in docs and planning |
| Market name | TBD | Customer-facing product name (decided later) |
| Code namespace | com.securedoc.platform | Java packages and Maven coordinates |
| Maven prefix | securedoc-* | Module artifact names |
| Codebase name | secure-document-platform | Repository and folder name |

## Rebranding Strategy

The code uses a generic, descriptive namespace (`securedoc`) that describes what the product does rather than a brand name. This allows:

1. **Market name independence** — Product can be branded/rebranded without code changes
2. **White-label potential** — Same codebase can serve multiple brands
3. **No class renames** — Domain classes use functional names (Document, VaultEntry, etc.)

## What Would Change in a Rebrand

- README and documentation references
- Docker image names
- Deployment labels and tags
- UI branding (logos, colors, text)

## What Would NOT Change

- Java package names (`com.securedoc.platform.*`)
- Maven artifact IDs (`securedoc-*`)
- Class names (DocumentPackage, ReviewTask, etc.)
- Database schema names
- Internal API paths

## Naming Rules for Development

- Never use "LexaVault" in code packages or class names
- Use descriptive domain names: Document, Artifact, ReviewTask, PolicyRule, VaultEntry, AccessRequest, AuditRecord
- Keep namespace generic and functional
- Document-facing strings (UI labels, API descriptions) can reference the market name when decided
