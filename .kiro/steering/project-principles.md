# Project Principles

- Modular monolith with hexagonal architecture boundaries
- Domain and application layers are framework-free (no Spring, no JPA, no external SDKs)
- Product-specific logic belongs in SecureDoc modules, not in IDP
- Generic code namespace — no brand/codename in packages or class names
- Local/demo first — cloud deployment comes later
- One unit at a time — complete each unit before starting the next
- Build must stay clean — never commit code that doesn't compile
