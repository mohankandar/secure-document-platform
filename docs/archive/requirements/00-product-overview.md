# Product Overview — Current V1 Direction

**Project codename:** LexaVault (documentation only; market name TBD)  
**Code namespace:** `com.securedoc.platform.*`

V1 is an AI-first automatic secure document-redaction platform. Customers provide documents and configured business identifiers; the platform understands sensitive content, locates it using trusted PDF/OCR geometry, applies explicit policy, permanently redacts, verifies, and delivers output. Humans intervene only for configured review or specific uncertain findings.

Core V1 baseline categories are SSN, Date of Birth, Credit Card Number, and Credit Score. The same Finding → Policy → Redaction pipeline is intentionally extensible to future semantic confidential content such as company secrets or proprietary sections.

See `.kiro/steering/requirements/v1-decision-freeze-2026-08-29.md` for the authoritative current decisions.
