# Manual Intake Primary-Identifier Regression

## Purpose

Verify that the tenant-configured **Primary** identifier is the BusinessRecord identity anchor, while secondary identifiers remain searchable/descriptive values that may repeat across records.

## Required scenario

With identifier configuration:

- Contract Number — Primary, Required
- Member Number — Required, non-Primary
- Service Entity — Required, non-Primary

Create or retain an existing record:

- Contract Number: `C-100`
- Member Number: `M-200`
- Service Entity: `000`

Then manually upload another package using:

- Contract Number: `C-101`
- Member Number: `M-200`
- Service Entity: `000`

Expected: a **new BusinessRecord and intake batch are created**. Repeated Member Number and Service Entity do not create an identifier conflict.

## Existing-record reuse

Upload another batch using exactly:

- Contract Number: `C-100`
- Member Number: `M-200`
- Service Entity: `000`

Expected: the existing `C-100` BusinessRecord is reused and a new intake/package is linked to it.

## Conflict protection

Upload using:

- Contract Number: `C-100`
- Member Number: `M-DIFFERENT`
- Service Entity: `000`

Expected: reject with `IDENTIFIER_CONFLICT` because the same Primary Contract Number cannot silently change its stored Member Number.

## Database expectations

After Liquibase 019:

- non-Primary identifier values may repeat across BusinessRecords;
- an active Primary identifier value remains unique within the tenant;
- a definition with duplicate existing values cannot be promoted to Primary.
