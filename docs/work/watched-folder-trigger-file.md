# Watched folder trigger file (requirements)

**Status:** requirements from the owner, 2026-11-20, recorded by the LexaVault design chat. Committed here 2026-11-22 so platform-core's intake task (P027) can build on it. Not designed or built yet. Task 0021 added a level setting per watched folder, built so that this file can override it later.

## Rules (owner)

- **The trigger file comes last.** The customer places the PDFs first, then the trigger file. When the trigger file appears, LexaVault takes in the whole folder. LexaVault documents this order for customers.
- **One trigger file per folder drop.** It describes the whole drop, never a single file on its own.
- **The format differs per customer:** for example XML, JSON, or a plain `.lck` marker file with no content. Each watched folder needs a setting for which file is the trigger and where each value comes from in it. Offered as part of the integration options.
- **What it can carry:**
  - the source system;
  - the contract (case) sensitivity level and each document's sensitivity level;
  - document names and document types (for example contract or loan);
  - the **primary identifier fields** the Business Administrator has defined for that entity (for example contract number, member number, first and last name).
- **Primary identifier:** LexaVault looks for the fields the Business Administrator has set as primary. If only the contract number is primary, it reads only the contract number.
- **If a value is missing,** the folder's own settings apply. A missing level means the folder's level; the folder's default is Standard.

## Split of work

- **platform-core (integration module, P027 intake):** watching the folder, the trigger-file setting and the reading of values from XML / JSON / marker files, waiting for the trigger, incomplete-drop detection.
- **LexaVault:** what the values mean (case, documents, levels, primary identifiers through case fields), its intake rules, and its screens.

## Open, for the design

- What happens when the trigger file names a document that isn't in the folder, or the folder holds a PDF the file doesn't name.
- How long LexaVault waits for a trigger file before it reports an incomplete drop (the watched folder already has an "incomplete window" setting).
- The error codes for a bad or unreadable trigger file (they go in the error code list).
