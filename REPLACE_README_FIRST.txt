LexaVault V1 post-freeze manual-intake identity correction candidate.

Use as a clean replacement, not an overlay.

Purpose:
- BusinessRecord resolution now uses only the tenant-configured Primary identifier.
- Secondary identifiers such as Member Number and Service Entity may repeat across different BusinessRecords.
- Same Primary identifier still reuses the existing BusinessRecord and rejects contradictory secondary values.
- Liquibase 019 updates PostgreSQL uniqueness rules accordingly.

After extraction run:
  .\Build-LexaVault-All.ps1

Then start the app and re-test the manual upload that previously showed:
  Business identifier conflicts with the existing record: Contract Number

Expected for a NEW Contract Number with a repeated Member Number/Service Entity:
  intake succeeds and creates a new BusinessRecord/package.
