# Redaction catalog and regulation packs

Source of truth: `RedactionCatalog` and `RegulationPack` (tasks 0062 and 0069). Task 0064's signature and face kinds are in the catalog too; see report 0064.

| Kind | Found by | Check | Off or on for a new entity |
|---|---|---|---|
| Social Security Number | AI and pattern, with an SSN label | A number never issued (000, 666, 9NN, group 00, serial 0000) gets less confidence, so a person looks at it | On |
| Date of birth | AI and pattern, with a birth label | A real date | On |
| Credit card number | AI and pattern, with a card label | Luhn check digit | Always on (locked) |
| Credit score | AI and pattern, with a score label | 300 to 850 | On |
| Bank routing number | Pattern: Routing, ABA, RTN, or a check's bottom line | ABA checksum | Off |
| Bank account number | Pattern: Account, Acct, A/C, or a check's bottom line | 4 to 17 digits; none exists | Off |
| ITIN | Pattern: ITIN label | 9NN and the issued groups | Off |
| EIN | Pattern: EIN, FEIN, tax ID label | Issued prefixes | Off |
| Driver's license number | Pattern: driver's license, DL, DL# label | Common US state formats, below | Off |
| Passport number | Pattern: Passport label | Up to 2 letters and 6 to 9 digits | Off |
| Card security code, PIN | Pattern: their label | 3 or 4 digits; 4 to 6 digits | Always on (locked) |

A number without its label is not found by a pattern.

**Driver's license formats** (dashes ignored): 5 to 14 digits; a letter and 4 to 14 digits; two letters and 4 to 7 digits; two letters, 6 digits and a letter (ID); three letters and 6 digits (ND); 3 digits, two letters, 4 digits (IA); 2 digits, three letters, 5 digits (NH); 7 digits and a letter (VT). Not covered: formats that mix letters and digits freely (for example Washington), and other countries.

| Pack | Kinds |
|---|---|
| GLBA / financial | SSN, ITIN, EIN, date of birth, bank account, bank routing, card, credit score, driver's license, passport, CVV, PIN |
| PCI DSS | card, CVV, PIN |
| HIPAA | SSN, ITIN, date of birth, bank account, card, driver's license, passport |
| FRCP 5.2 | SSN, ITIN, EIN, date of birth, bank account, card |

**Digitally signed PDFs** are processed as any other. The audit trail says "Original was digitally signed; the signature no longer applies to the redacted copy", without the signer; the compliance report counts them. Nothing is added to the page.
