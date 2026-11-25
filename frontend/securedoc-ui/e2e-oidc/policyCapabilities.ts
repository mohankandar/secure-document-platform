/**
 * The capabilities the backend grants each role, copied exactly from
 * backend/securedoc-api/.../security/ProductAuthorizationPolicy.java (U018).
 *
 * Every e2e persona takes its capabilities from here, so a test never signs in with a list
 * the server would not send. `src/security/policyCapabilities.test.ts` reads the Java file and
 * fails if this copy drifts from it.
 *
 * Compliance report (U022): COMPLIANCE_REPORT_FULL creates either edition (the IT Administrator may untick
 * "Include full audit trail"); COMPLIANCE_REPORT_SUMMARY creates the summary only. The server checks this in
 * ComplianceReportController, not by granting the IT Administrator a second capability.
 */
const PROCESSOR = ['DASHBOARD_ACCESS', 'DOCUMENT_INTAKE', 'CASE_FIELD_SENSITIVE_VIEW', 'MANUAL_REVIEW', 'FINDING_DECISION', 'REDACTION_ADJUST', 'REVIEW_SUBMIT',
  'EXPORT_RESEND'];
const REPORT_READ = ['REPORT_EXECUTIVE_VIEW', 'REPORT_OPERATIONAL_VIEW'];

export const POLICY_CAPABILITIES: Readonly<Record<string, readonly string[]>> = {
  DOCUMENT_PROCESSOR: PROCESSOR,
  DOCUMENT_SUPERVISOR: [...PROCESSOR, 'PACKAGE_APPROVE_DELIVERY', 'PACKAGE_REPROCESS', 'PACKAGE_REMOVE_FROM_DASHBOARD'],
  BUSINESS_ADMIN: [...REPORT_READ, 'REPORT_EXPORT', 'COMPLIANCE_REPORT_SUMMARY', 'TENANT_POLICY_MANAGE', 'TENANT_IDENTIFIER_MANAGE'],
  REPORT_VIEWER: ['REPORT_EXECUTIVE_VIEW'],
  TENANT_IT_ADMIN: ['TENANT_INTEGRATION_MANAGE', 'TENANT_AUTH_MANAGE', 'TECHNICAL_EXCEPTION_VIEW', 'TECHNICAL_ADMIN_VIEW',
    'TECHNICAL_RETRY', 'TECHNICAL_HOLD_MANAGE', 'AUDIT_VIEW', 'COMPLIANCE_REPORT_FULL'],
  PLATFORM_OPERATIONS_ADMIN: ['TECHNICAL_EXCEPTION_VIEW', 'TECHNICAL_ADMIN_VIEW', 'AUDIT_VIEW', 'PLATFORM_DIAGNOSTICS', 'PLATFORM_OPERATIONS'],
};

/** What `/me` sends for these roles: the union of each role's list, sorted. Unknown roles get nothing, as in the policy. */
export function capabilitiesFor(...roles: string[]): string[] {
  const all = new Set<string>();
  for (const role of roles) for (const capability of POLICY_CAPABILITIES[role] ?? []) all.add(capability);
  return [...all].sort();
}
