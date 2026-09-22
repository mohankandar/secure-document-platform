#requires -Version 5.1
<#
.SYNOPSIS
    Deletes exactly ONE disposable acceptance-test package and its child rows.

.DESCRIPTION
    Reusable test-tooling helper. Removes a single package created by the acceptance harness,
    together with the rows that reference it, in foreign-key-safe order.

    SAFETY:
      - Operates on exactly one package id, validated as a UUID.
      - Deletes only rows whose package_id (or whose parent document's id) matches that one package.
      - Never deletes unrelated packages or historical data. There is no bulk/wildcard delete.
      - Runs inside a single transaction (BEGIN/COMMIT) so a failure leaves the DB unchanged.

    The child-table foreign-key columns were confirmed against the live schema:
      children of documents:          artifacts.document_id, findings.document_id,
                                       vault_entries.document_id, document_export_records.document_id,
                                       external_intake_documents.received_document_id,
                                       delivery_submission_documents.document_id,
                                       derived_deliveries.source_document_id
      children of document_packages:  documents, review_tasks, vault_entries, package_lifecycles,
                                       package_export_records, external_intakes, delivery_submissions,
                                       derived_deliveries, package_processing_intelligence_snapshots,
                                       package_processing_leases, processing_storage_cycle_pins
      package-scoped by column (no FK): processing_incidents (its processing_incident_events rows
                                       cascade via FK). Added for the Technical Attention / recovery-
                                       exhaustion path so Test 2's disposable package cleans up fully.

.PARAMETER PackageId
    The disposable package id to remove.

.OUTPUTS
    $true if the package no longer exists after the call; otherwise throws.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string] $PackageId
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ($PackageId -notmatch '^[0-9a-fA-F-]{36}$') {
    throw "Remove-TestPackage: PackageId '$PackageId' is not a valid UUID."
}

$runQuery = Join-Path $PSScriptRoot 'Invoke-TestDbQuery.ps1'

$id = "'$PackageId'"
$docSel = "SELECT id FROM documents WHERE package_id = $id"

# Single transaction; ON_ERROR_STOP=1 (set by Invoke-TestDbQuery) aborts + rolls back on any error.
$sql = @"
BEGIN;
DELETE FROM artifacts WHERE document_id IN ($docSel);
DELETE FROM findings WHERE document_id IN ($docSel);
DELETE FROM document_export_records WHERE document_id IN ($docSel);
DELETE FROM external_intake_documents WHERE received_document_id IN ($docSel);
DELETE FROM delivery_submission_documents WHERE document_id IN ($docSel);
DELETE FROM derived_deliveries WHERE source_document_id IN ($docSel);
DELETE FROM derived_deliveries WHERE package_id = $id;
DELETE FROM vault_entries WHERE document_id IN ($docSel);
DELETE FROM vault_entries WHERE package_id = $id;
DELETE FROM review_tasks WHERE package_id = $id;
DELETE FROM package_lifecycles WHERE package_id = $id;
DELETE FROM package_export_records WHERE package_id = $id;
DELETE FROM external_intakes WHERE package_id = $id;
DELETE FROM delivery_submissions WHERE package_id = $id;
DELETE FROM package_processing_intelligence_snapshots WHERE package_id = $id;
DELETE FROM package_processing_leases WHERE package_id = $id;
DELETE FROM processing_storage_cycle_pins WHERE package_id = $id;
DELETE FROM processing_incidents WHERE package_id = $id;
DELETE FROM documents WHERE package_id = $id;
DELETE FROM document_packages WHERE id = $id;
COMMIT;
SELECT COUNT(*) FROM document_packages WHERE id = $id;
"@

$result = @(& $runQuery -Sql $sql)
$remaining = if ($result.Count -gt 0) { [int]("$($result[-1])".Trim()) } else { 0 }

if ($remaining -ne 0) {
    throw "Remove-TestPackage: package $PackageId still present after cleanup (remaining=$remaining)."
}

Write-Host "  [cleanup] removed disposable package $PackageId and its child rows."
return $true
