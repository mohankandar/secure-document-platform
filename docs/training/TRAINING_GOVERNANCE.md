# Training Documentation Governance

## 1. Working agreement

Add the following rule to the LexaVault repository working agreement:

> **Training documentation is part of Definition of Done.** Any user-visible, support-visible, operational, security, or workflow change is incomplete until all affected training and support documentation has been updated. Repository documentation is the version-controlled source of truth.

## 2. Required audiences

Each feature must be evaluated for impact on three audiences:

1. **Business**
   - What changed in the user's workflow?
   - What can the user do?
   - What decisions are they responsible for?
   - What warnings or confirmations do they see?
   - What should they do if the normal path fails?

2. **Tenant IT**
   - What support state or incident can the tenant see?
   - What can Tenant IT safely retry, configure, or diagnose?
   - What identifiers are needed when escalating?
   - What data must never be copied into a ticket?
   - What business-continuity behavior may occur independently of IT resolution?

3. **Platform Operations / Support**
   - What processing stage/reason codes exist?
   - How is the incident correlated across package/document/request boundaries?
   - What processing fingerprint is available?
   - How is business impact distinguished from technical resolution?
   - What safe DB/log/trace data can be used?
   - What is the escalation and resolution procedure?

## 3. Feature Definition of Done

For every bounded feature/unit:

- [ ] Requirements / acceptance criteria frozen for the unit
- [ ] Code complete
- [ ] Local functional testing complete
- [ ] Automated tests green
- [ ] Build green
- [ ] Security / tenant boundaries verified
- [ ] Kiro verification performed when the checkpoint materially benefits from local-machine independent review
- [ ] Repository continuity / backlog updated
- [ ] Business training updated if user-visible
- [ ] Tenant IT guide updated if support/configuration/incident behavior changed
- [ ] Platform Operations guide updated if processing/diagnostic/operational behavior changed
- [ ] Training changelog entry added
- [ ] Screenshots refreshed if the accepted UI changed
- [ ] Commit / tag / recoverable baseline created as appropriate

## 4. Training status labels

Use one of:

- **DRAFT** — design not yet implemented
- **IN VALIDATION** — implemented and being tested; behavior may still change
- **ACCEPTED** — locally accepted and build/test gates green
- **VERIFIED / FROZEN** — independently verified and treated as baseline behavior
- **DEPRECATED** — retained only for historical reference

Do not train customers or support staff from DRAFT or IN VALIDATION sections unless explicitly marked as a preview.

## 5. Screenshot policy

- Use screenshots from an accepted build whenever possible.
- Use local/demo data only.
- Avoid showing secrets, tokens, production hostnames, personal information, raw sensitive document content, or customer-specific confidential information.
- If UI wording changes, replace the screenshot rather than accumulating contradictory screenshots.
- Keep screenshot filenames stable when the conceptual screen remains the same so downstream documentation links remain valid.

## 6. Privacy rules for all training material

Never include:
- real SSNs
- real PAN/card numbers
- real DOBs
- real credit scores tied to a person
- raw OCR text from customer documents
- raw prompts
- raw AI responses
- credentials/tokens
- unrestricted production logs containing customer content

Safe examples should use synthetic/local-demo data.

## 7. Publication model

Repository Markdown is the source of truth.

Optional published forms:
- Google Workspace
- internal knowledge base
- customer-facing help center
- release notes
- onboarding handbooks

Published copies must identify their source version/date and be updated when the repository training changelog indicates an affected feature.
