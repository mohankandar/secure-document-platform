export type RoleOption = { value: string; label: string };

export const ROLE_OPTIONS: RoleOption[] = [
  { value: 'DOCUMENT_PROCESSOR', label: 'Document Processor' },
  { value: 'DOCUMENT_SUPERVISOR', label: 'Document Supervisor' },
  { value: 'BUSINESS_ADMIN', label: 'Business Administrator' },
  { value: 'REPORT_VIEWER', label: 'Report Viewer' },
  { value: 'TENANT_IT_ADMIN', label: 'IT Administrator' },
  { value: 'PLATFORM_OPERATIONS_ADMIN', label: 'Platform Operations Administrator' },
];

export function hasCapability(capabilities: string[], capability: string): boolean {
  return capabilities.includes(capability);
}

export function canAccessSurface(capabilities: string[], surface: 'dashboard' | 'upload' | 'review' | 'reports' | 'reportExport' | 'businessAdmin' | 'integrations' | 'technicalAdmin' | 'audit'): boolean {
  switch (surface) {
    case 'dashboard': return hasCapability(capabilities, 'DASHBOARD_ACCESS');
    case 'upload': return hasCapability(capabilities, 'DOCUMENT_INTAKE');
    case 'review': return hasCapability(capabilities, 'MANUAL_REVIEW');
    case 'reports': return hasCapability(capabilities, 'REPORT_EXECUTIVE_VIEW');
    case 'reportExport': return hasCapability(capabilities, 'REPORT_EXPORT');
    case 'businessAdmin': return hasCapability(capabilities, 'TENANT_POLICY_MANAGE') || hasCapability(capabilities, 'TENANT_IDENTIFIER_MANAGE');
    case 'integrations': return hasCapability(capabilities, 'TENANT_INTEGRATION_MANAGE');
    case 'technicalAdmin': return hasCapability(capabilities, 'TECHNICAL_ADMIN_VIEW');
    case 'audit': return hasCapability(capabilities, 'AUDIT_VIEW');
  }
}
