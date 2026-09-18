LexaVault V1 — Watched-Folder Golden-Path R4

Base:
- Starts from the VERIFIED/FROZEN PDF Artifact Quality + EIN/SSN Precision baseline.
- R11 local-rule-based detector remains 1.6.0.
- OCR guard remains 1.4.0.
- Liquibase remains 54 changeSets.

Changes in this R4 candidate:
1. LOCAL watched-folder intake is enabled by default.
2. LOCAL automatic watched-folder scheduling is enabled by default at 10 seconds.
3. LOCAL accepted folders move to _archive; rejected folders move to _problem.
4. Duplicate terminal folders now use the accepted source disposition so automatic polling does not rediscover the same duplicate folder forever.
5. Start-LexaVault-Local.ps1 creates and prints backend\watched-intake.
6. Synthetic golden-path watched-intake templates are included under test-data\golden-path-watched-intake-template.
7. Stage-GoldenPath-WatchedIntake.ps1 publishes complete cases safely with manifest.json last.
8. Added LocalWatchedFolderSourceAdapterTest with terminal-disposition coverage.

Production defaults remain opt-in; this automatic behavior is the LOCAL profile only.
Environment overrides remain available:
- SECUREDOC_WATCHED_FOLDER_ENABLED
- SECUREDOC_WATCHED_FOLDER_ROOT_PATH
- SECUREDOC_WATCHED_FOLDER_TRIGGER_FILE_NAME
- SECUREDOC_WATCHED_FOLDER_ACCEPTED_BEHAVIOR
- SECUREDOC_WATCHED_FOLDER_REJECTED_BEHAVIOR
- SECUREDOC_WATCHED_FOLDER_SCHEDULING_ENABLED
- SECUREDOC_WATCHED_FOLDER_SCAN_INTERVAL_MS

Recommended validation:
1. Run build-all.cmd.
2. Keep Document Intelligence DISABLED.
3. Start LOCAL.
4. Stage cases one at a time first:
   .\Stage-GoldenPath-WatchedIntake.ps1 -Case 01-clean
   .\Stage-GoldenPath-WatchedIntake.ps1 -Case 02-auto-redaction
   ...
5. For duplicate validation, stage 06-duplicate-a, wait until accepted/archive, then stage 06-duplicate-b.
6. Verify the second duplicate creates no new package and is archived as terminal duplicate intake.
