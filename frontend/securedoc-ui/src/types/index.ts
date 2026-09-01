export interface BusinessIdentifier {
  key: string;
  label: string;
  value: string;
  primary: boolean;
  searchable: boolean;
  displayable: boolean;
  displayOrder: number;
}

export interface IdentifierDefinition {
  key: string;
  label: string;
  required: boolean;
  primary: boolean;
  searchable: boolean;
  extractable: boolean;
  displayable: boolean;
  displayOrder: number;
}


export interface BusinessAdminIdentifierDefinition extends IdentifierDefinition {
  id: string;
}

export type BusinessReviewMode = 'UNCERTAIN_ONLY' | 'REVIEW_ALL';

export interface SensitiveDataPolicyConfiguration {
  enabledEntityTypes: string[];
  autoRedactThreshold: number;
  reviewMode: BusinessReviewMode;
  maskSsnLastFour: boolean;
}

export interface LifecyclePolicyConfiguration {
  originalRetentionHours: number;
  redactedCacheHours: number;
  neverExportedHoldHours: number;
  finalApprovalRequired: boolean;
  maximumSuccessfulExportCycles: number;
  maximumRedactedCacheHours: number;
}

export interface DashboardAdminColumnConfiguration {
  key: string;
  label: string;
  kind: 'IDENTIFIER' | 'SYSTEM' | string;
  primary: boolean;
  allowed: boolean;
  defaultVisible: boolean;
  displayOrder: number;
}

export interface BusinessAdminConfiguration {
  identifiers: BusinessAdminIdentifierDefinition[];
  dashboardColumns: DashboardAdminColumnConfiguration[];
  sensitiveDataPolicy: SensitiveDataPolicyConfiguration;
  lifecyclePolicy: LifecyclePolicyConfiguration;
}

export interface BusinessAdminIdentifierDraft {
  id?: string;
  label: string;
  required: boolean;
  primary: boolean;
  searchable: boolean;
  extractable: boolean;
  displayable: boolean;
}

export interface PackageSummary {
  packageId: string;
  tenantId?: string;
  packageType: string;
  status: string;
  businessIdentifiers: BusinessIdentifier[];
  documentCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface PackageDetail extends PackageSummary {
  metadata?: Record<string, string>;
  documents: DocumentSummary[];
}

export interface DocumentSummary {
  documentId: string;
  fileName?: string;
  mimeType: string;
  sizeBytes: number;
  documentType?: string;
  status: string;
}

export interface CreatePackageRequest {
  packageType: string;
  businessIdentifiers: Record<string, string>;
  metadata?: Record<string, string>;
}

export interface PackageStatusResult {
  packageId: string;
  status: string;
}

export interface DocumentUploadResult {
  documentId: string;
  artifactId: string;
  status: string;
}

export interface ReviewTaskResult {
  reviewTaskId: string;
  tenantId?: string;
  packageId: string;
  status: string;
  assignedTo?: string;
  createdAt: string;
  updatedAt: string;
}

export interface AccessRequestResult {
  id: string;
  tenantId: string;
  vaultEntryId: string;
  artifactId: string;
  requestedBy: string;
  status: string;
  requestedAt: string;
  expiresAt: string;
  decidedAt?: string;
}

export interface VaultEntryResult {
  vaultEntryId: string;
  packageId: string;
  documentId: string;
  artifactId: string;
  artifactType: string;
  registeredAt: string;
}

export interface ApiError {
  errorCode?: string;
  message?: string;
  correlationId?: string;
}

export interface AuditRecordResult {
  id: string;
  actorId: string;
  action: string;
  resourceType: string;
  resourceId: string;
  outcome: string;
  correlationId: string;
  detail?: string;
  occurredAt: string;
}

export interface ReviewBoundingBox {
  left: number;
  top: number;
  width: number;
  height: number;
}

export interface FocusedReviewItem {
  findingId: string;
  packageId: string;
  documentId: string;
  fileName: string;
  entityType: string;
  confidence: number;
  pageNumber: number;
  hasBoundingBox: boolean;
  boundingBox?: ReviewBoundingBox | null;
  reason: 'LOW_CONFIDENCE' | 'LOCATION_UNCERTAIN' | 'POLICY_REVIEW' | string;
}


export interface FocusedReviewQueue {
  items: FocusedReviewItem[];
  totalItems: number;
}

export interface ReviewPagePreview {
  blob: Blob;
  pageNumber: number;
}

export interface ReviewResolutionResult {
  findingId: string;
  packageId: string;
  status: string;
  processingResumed: boolean;
}


export interface PackageLifecycleSummary {
  packageId: string;
  successfulExportCycles: number;
  maximumExportCycles: number;
  canReprocess: boolean;
  finalApprovalRequired: boolean;
  finalApprovalGranted: boolean;
  lastExportedAt?: string | null;
  originalDeleteAt?: string | null;
  redactedDeleteAt?: string | null;
  technicalHoldUntil?: string | null;
  redactedPurgedAt?: string | null;
  originalPurgedAt?: string | null;
  purgedAt?: string | null;
  lastDestinationType?: string | null;
}

export type DashboardStage =
  | 'ALL'
  | 'PROCESSING'
  | 'NEEDS_REVIEW'
  | 'NEEDS_APPROVAL'
  | 'EXPORTED'
  | 'TECHNICAL_ATTENTION'
  | 'REJECTED';

export interface DashboardColumnDefinition {
  key: string;
  label: string;
  kind: 'IDENTIFIER' | 'SYSTEM' | string;
  primary: boolean;
  searchable: boolean;
  defaultVisible: boolean;
  displayOrder: number;
}

export interface DashboardIdentifierValue {
  key: string;
  label: string;
  value: string;
  primary: boolean;
  displayOrder: number;
}

export interface DashboardOverview {
  total: number;
  processing: number;
  needsReview: number;
  needsApproval: number;
  exported: number;
  technicalAttention: number;
  rejected: number;
}

export interface DashboardItem {
  packageId: string;
  packageType: string;
  status: string;
  stage: DashboardStage;
  attention: 'NONE' | 'REVIEW' | 'APPROVAL' | 'TECHNICAL' | 'REJECTED' | string;
  identifiers: DashboardIdentifierValue[];
  documentCount: number;
  successfulExportCycles: number;
  lastExportedAt?: string | null;
  originalDeleteAt?: string | null;
  technicalHoldUntil?: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface OperationsDashboard {
  overview: DashboardOverview;
  columns: DashboardColumnDefinition[];
  items: DashboardItem[];
  totalItems: number;
  offset: number;
  limit: number;
}

export interface OperationalReports {
  generatedAt: string;
  period: {
    from: string;
    to: string;
    previousFrom: string;
    previousTo: string;
    source: string;
    outcome: string;
  };
  executive: {
    batches: number;
    previousBatches: number;
    documents: number;
    pages: number;
    completed: number;
    previousCompleted: number;
    delivered: number;
    previousDelivered: number;
    completionRate: number;
    deliveryRate: number;
    medianDeliveryHours: number;
    p95DeliveryHours: number;
    straightThroughRate: number;
    reviewRate: number;
    currentBacklog: number;
    currentAttention: number;
    oldestBacklogHours: number;
    trend: Array<{ bucket: string; received: number; delivered: number }>;
  };
  intake: {
    totalBatches: number;
    manual: number;
    api: number;
    watchedFolder: number;
    waitingForTrigger: number;
    duplicateObservations: number;
    rejectedObservations: number;
  };
  processing: {
    processing: number;
    needsReview: number;
    needsApproval: number;
    completed: number;
    technicalAttention: number;
    rejected: number;
  };
  export: {
    exportedPackages: number;
    successfulExportCycles: number;
    pendingFinalApproval: number;
  };
  retention: {
    originalsDueWithin24Hours: number;
    redactedCopiesDueWithin24Hours: number;
    technicalHoldsDueWithin24Hours: number;
    purgedPackages: number;
    originalsOverdue: number;
    redactedCopiesOverdue: number;
  };
}

export interface PlatformHealthItem {
  key: string;
  label: string;
  category: string;
  provider: string;
  status: string;
  configured: boolean;
  detail: string;
}

export interface TechnicalExceptionItem {
  packageId: string;
  reasonCode: string;
  status: string;
  documentCount: number;
  failedAt: string;
  technicalHoldUntil?: string | null;
}

export interface WatchedFolderAdminStatus {
  enabled: boolean;
  schedulingEnabled: boolean;
  scanIntervalMs: number;
  rootPath: string;
  triggerFileName: string;
  incompleteVisibilityMinutes: number;
  acceptedBehavior: string;
  rejectedBehavior: string;
  lastScanAt?: string | null;
  lastScanOutcome: string;
  lastScanResult?: {
    discovered: number;
    waitingForTrigger: number;
    accepted: number;
    rejected: number;
    duplicates: number;
  } | null;
}

export interface PlatformAdminOverview {
  generatedAt: string;
  providers: PlatformHealthItem[];
  watchedFolder: WatchedFolderAdminStatus;
  resilience: {
    maxAttempts: number;
    initialBackoffMs: number;
    aiSecondaryEnabled: boolean;
    aiSecondaryProvider: string;
    aiSecondaryModel: string;
  };
  integrations: {
    deliveryType: string;
    apiCallbackConfigured: boolean;
    s3Configured: boolean;
    identityMode: string;
    productionIdentityConfigured: boolean;
  };
  technicalExceptions: TechnicalExceptionItem[];
}

export interface CurrentUser {
  username: string;
  roles: string[];
  tenantName: string;
  tenantSlug: string;
  authenticationMode: 'LOCAL' | 'OIDC' | string;
  capabilities: string[];
}

export interface TechnicalExceptionDetail {
  packageId: string;
  documentId?: string | null;
  status: string;
  failureCategory: string;
  failedStage: string;
  retryable: boolean;
  failedAt: string;
  correlationId?: string | null;
  originalRetainedUntil?: string | null;
  explanation: string;
  operatorDiagnostics?: {
    dependency: string;
    internalErrorCode: string;
    attempts: number;
    environment: string;
  } | null;
}
