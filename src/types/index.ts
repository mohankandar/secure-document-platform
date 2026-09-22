export type BusinessIdentifierValidationType = 'TEXT' | 'NUMERIC' | 'ALPHANUMERIC' | 'EMAIL' | 'UUID' | 'ALLOW_LIST' | 'CUSTOM_PATTERN';
export type BusinessIdentifierCaseNormalization = 'PRESERVE' | 'UPPER' | 'LOWER';

export interface BusinessIdentifierValidationConfiguration {
  validationType: BusinessIdentifierValidationType;
  exactLength?: number | null;
  minLength?: number | null;
  maxLength?: number | null;
  caseNormalization: BusinessIdentifierCaseNormalization;
  stripSeparators: boolean;
  allowedValues: string[];
  customPattern?: string | null;
}

export interface BusinessIdentifier {
  key: string;
  label: string;
  value: string;
  primary: boolean;
  searchable: boolean;
  displayable: boolean;
  displayOrder: number;
}

export interface IdentifierDefinition extends BusinessIdentifierValidationConfiguration {
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

export type DocumentIntelligenceMode = 'DISABLED' | 'OBSERVE' | 'ENABLED';

export interface IntelligenceBundleConfiguration {
  version: string;
  status: 'CANDIDATE' | 'PROMOTED' | 'RETIRED' | string;
  modelVersion: string;
  promptVersion: string;
  detectorVersion: string;
  ocrRulesVersion: string;
  profileVersion: string;
  validationRulesVersion: string;
  thresholdsVersion: string;
  description?: string | null;
}

export interface DocumentIntelligenceConfiguration {
  mode: DocumentIntelligenceMode;
  activeBundleVersion: string;
  lastKnownGoodBundleVersion: string;
  configurationVersion: number;
  baselineDiscoveryRequired: boolean;
  availableBundles: IntelligenceBundleConfiguration[];
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


export interface ReportingDimensionConfiguration {
  definitionId: string;
  key: string;
  label: string;
  enabled: boolean;
  displayOrder: number;
}

export interface ReportingConfiguration {
  dimensions: ReportingDimensionConfiguration[];
  deliveryTargetMinutes: number;
  deliveryTargetPercent: number;
  straightThroughTargetPercent: number;
  reviewWaitTargetMinutes: number;
}

export interface BusinessAdminConfiguration {
  identifiers: BusinessAdminIdentifierDefinition[];
  dashboardColumns: DashboardAdminColumnConfiguration[];
  sensitiveDataPolicy: SensitiveDataPolicyConfiguration;
  lifecyclePolicy: LifecyclePolicyConfiguration;
  documentIntelligence: DocumentIntelligenceConfiguration;
  reportingConfiguration: ReportingConfiguration;
}

export interface BusinessAdminIdentifierDraft extends BusinessIdentifierValidationConfiguration {
  id?: string;
  label: string;
  required: boolean;
  primary: boolean;
  searchable: boolean;
  extractable: boolean;
  displayable: boolean;
}


export type RoutingMarkerType = 'BARCODE' | 'QR_CODE' | 'TEXT_PATTERN';
export type RoutingScanRegion = 'TOP_RIGHT' | 'BOTTOM_LEFT' | 'BOTH';

export interface RoutingDestinationOption {
  connectionId: string;
  name: string;
  providerType: 'HTTP_API' | 'FILE_SHARE' | string;
  enabled: boolean;
  healthStatus: string;
}

export interface RoutingRule {
  ruleId: string;
  destinationLabel: string;
  markerType: RoutingMarkerType;
  markerValue: string;
  scanRegion: RoutingScanRegion;
  destinationConnectionId: string;
  enabled: boolean;
}

export interface RoutingRuleRequest {
  destinationLabel: string;
  markerType: RoutingMarkerType;
  markerValue: string;
  scanRegion: RoutingScanRegion;
  destinationConnectionId: string;
  enabled: boolean;
}

export interface DerivedDelivery {
  derivedDeliveryId: string;
  packageId: string;
  sourceDocumentId: string;
  exportCycle: number;
  destinationLabel?: string;
  markerType?: RoutingMarkerType;
  markerValue?: string;
  pageNumbers: number[];
  fileName?: string;
  status: 'PENDING' | 'DELIVERING' | 'RETRY_PENDING' | 'DELIVERED' | 'ATTENTION_REQUIRED' | string;
  attemptCount: number;
  failureCategory?: string;
  retryable: boolean;
  destinationReference?: string;
  deliveredAt?: string;
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


export interface ManualFallbackDocument {
  documentId: string;
  fileName: string;
  pageCount: number;
}

export interface ManualFallbackRegion extends ReviewBoundingBox {
  findingId: string;
  documentId: string;
  pageNumber: number;
  entityType: string;
}

export interface ManualFallbackDetail {
  packageId: string;
  status: string;
  documents: ManualFallbackDocument[];
  regions: ManualFallbackRegion[];
  enabledEntityTypes: string[];
}

export interface ManualFallbackPagePreview {
  blob: Blob;
  pageNumber: number;
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
    periodIntakeDelivered: number;
    previousPeriodIntakeDelivered: number;
    completionRate: number;
    deliveryRate: number;
    periodIntakeDeliveryRate: number;
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
    scanner: number;
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
  timing: {
    medianTurnaroundHours: number;
    p95TurnaroundHours: number;
    medianActiveProcessingHours: number;
    p95ActiveProcessingHours: number;
    medianReviewWaitHours: number;
    medianReviewHandlingHours: number;
    turnaroundSamples: number;
    activeProcessingSamples: number;
    reviewWaitSamples: number;
    reviewHandlingSamples: number;
  };
  backlog: {
    underOneHour: number;
    oneToFourHours: number;
    fourToTwentyFourHours: number;
    oneToThreeDays: number;
    overThreeDays: number;
    oldestHours: number;
  };
  usage: {
    packages: number;
    documents: number;
    pages: number;
    averageDocumentsPerPackage: number;
    averagePagesPerPackage: number;
    reviewedPackages: number;
    straightThroughPackages: number;
    reprocessedPackages: number;
  };
  serviceLevel: {
    deliveryTargetMinutes: number;
    deliveryTargetPercent: number;
    deliveryWithinTargetRate: number;
    deliveryEligible: number;
    deliveryBreaches: number;
    straightThroughTargetPercent: number;
    straightThroughActualPercent: number;
    reviewWaitTargetMinutes: number;
    reviewWithinTargetRate: number;
    reviewEligible: number;
    reviewBreaches: number;
  };
  dimensions: Array<{
    key: string;
    label: string;
    values: Array<{ value: string; packages: number; delivered: number; deliveryRate: number; medianTurnaroundHours: number }>;
  }>;

}


export type IntegrationPurpose = 'INGESTION_SOURCE' | 'SDV_DELIVERY' | 'DERIVED_DOCUMENT_DELIVERY';
export type IntegrationDirection = 'INBOUND' | 'OUTBOUND';
export type IntegrationProviderType = 'PUSH_API' | 'HTTP_API' | 'FILE_SHARE' | 'WATCHED_FOLDER' | 'SCANNER';
export type IntegrationAuthenticationType = 'NONE' | 'BASIC' | 'API_KEY' | 'JWT' | 'OAUTH2_CLIENT_CREDENTIALS' | 'MTLS';
export type IntegrationAcknowledgementMode = 'NONE' | 'HTTP_SUCCESS' | 'DESTINATION_RECEIPT' | 'DURABLE_FILE_WRITE' | 'SOURCE_PICKUP_ACK' | 'SDV_SUBMISSION_ACK';
export type IntegrationPollingScheduleType = 'INTERVAL' | 'DAILY';

export interface IntegrationConnection {
  connectionId: string;
  name: string;
  description?: string | null;
  purpose: IntegrationPurpose;
  direction: IntegrationDirection;
  providerType: IntegrationProviderType;
  authenticationType: IntegrationAuthenticationType;
  clientPrincipal?: string | null;
  endpoint?: string | null;
  fileShareRoot?: string | null;
  secretConfigured: boolean;
  timeoutSeconds: number;
  retryMaxAttempts: number;
  incompleteWindowHours: number;
  maxDocumentsPerIntake: number;
  maxPdfSizeBytes: number;
  maxPdfPages: number;
  pollingScheduleType?: IntegrationPollingScheduleType | null;
  pollingIntervalMinutes?: number | null;
  pollingLocalTime?: string | null;
  pollingTimeZone?: string | null;
  pollingBatchLimit?: number | null;
  acknowledgementMode: IntegrationAcknowledgementMode;
  enabled: boolean;
  configurationVersion: number;
  healthStatus: string;
  lastSuccessAt?: string | null;
  lastFailureAt?: string | null;
  lastFailureCategory?: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface IntegrationConnectionRequest {
  name: string;
  description?: string;
  purpose: IntegrationPurpose;
  direction: IntegrationDirection;
  providerType: IntegrationProviderType;
  authenticationType: IntegrationAuthenticationType;
  clientPrincipal?: string;
  endpoint?: string;
  fileShareRoot?: string;
  secretReference?: string;
  clearSecretReference?: boolean;
  timeoutSeconds?: number;
  retryMaxAttempts?: number;
  incompleteWindowHours?: number;
  maxDocumentsPerIntake?: number;
  maxPdfSizeBytes?: number;
  maxPdfPages?: number;
  pollingScheduleType?: IntegrationPollingScheduleType;
  pollingIntervalMinutes?: number;
  pollingLocalTime?: string;
  pollingTimeZone?: string;
  pollingBatchLimit?: number;
  acknowledgementMode: IntegrationAcknowledgementMode;
  enabled?: boolean;
}


export interface SdvConnectionTestResult {
  connectionId: string;
  healthStatus: string;
  secretConfigured: boolean;
  configurationVersion: number;
}

export interface PollingRun {
  runId: string; connectionId: string; trigger: 'SCHEDULED' | 'MANUAL'; status: 'RUNNING' | 'SUCCESS' | 'PARTIAL' | 'FAILED';
  startedAt: string; completedAt?: string | null; packagesDiscovered: number; documentsExpected: number;
  documentsRetrieved: number; packagesAcknowledged: number; failureCategory?: string | null; failureDetail?: string | null;
}

export interface PollingConnectionStatus {
  connectionId: string; name: string; enabled: boolean; healthStatus: string; nextScheduledAt?: string | null; recentRuns: PollingRun[];
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
  processingDirectory: string;
  coordinationEnabled: boolean;
  nodeId?: string | null;
  nodeInstanceId?: string | null;
  scannerRole?: string | null;
  leaseOwner?: string | null;
  fencingToken?: number | null;
  leaseAcquiredAt?: string | null;
  leaseHeartbeatAt?: string | null;
  leaseExpiresAt?: string | null;
  takeoverCount?: number;
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

export interface ProductTelemetryStatus {
  mode: 'OFF' | 'BASIC' | 'ENHANCED' | string;
  enabled: boolean;
  installationIdConfigured: boolean;
  privacyContract: string;
  eventAllowList: string[];
  prohibitedData: string[];
}

export interface PlatformAdminOverview {
  generatedAt: string;
  providers: PlatformHealthItem[];
  watchedFolder: WatchedFolderAdminStatus;
  resilience: {
    maxAttempts: number;
    initialBackoffMs: number;
    packageProcessingMaxAttempts: number;
    aiSecondaryEnabled: boolean;
    aiSecondaryProvider: string;
    aiSecondaryModel: string;
  };
  integrations: {
    deliveryType: string;
    sdvConfigured: boolean;
    identityMode: string;
    productionIdentityConfigured: boolean;
  };
  technicalExceptions: TechnicalExceptionItem[];
}

export type ProcessingStorageProviderType = 'LOCAL_FILESYSTEM' | 'SHARED_FILESYSTEM';
export type ProcessingStorageProviderStatus = 'CANDIDATE' | 'AVAILABLE' | 'RETIRED' | string;

export interface ProcessingStorageProvider {
  id: string;
  name: string;
  providerType: ProcessingStorageProviderType | string;
  rootPath: string;
  status: ProcessingStorageProviderStatus;
  configurationVersion: number;
}

export interface ProcessingStorageAssignment {
  id: string;
  tenantId?: string | null;
  providerId: string;
  scope: 'DEPLOYMENT_DEFAULT' | 'TENANT_OVERRIDE' | string;
  activatedAt: string;
}

export interface ProcessingStorageNodeValidation {
  nodeId: string;
  processInstanceId: string;
  providerId: string;
  status: 'HEALTHY' | 'UNHEALTHY' | string;
  failureCode?: string | null;
  checkedAt: string;
}

export interface ProcessingStorageTenant {
  tenantId: string;
  name: string;
  slug: string;
  status: string;
}

export interface ProcessingStorageOverview {
  ready: boolean;
  deploymentDefault?: ProcessingStorageAssignment | null;
  tenantOverrides: ProcessingStorageAssignment[];
  providers: ProcessingStorageProvider[];
  nodeValidations: ProcessingStorageNodeValidation[];
  tenants: ProcessingStorageTenant[];
  activeNodeCount: number;
}

export interface CreateProcessingStorageProviderRequest {
  name: string;
  providerType: ProcessingStorageProviderType;
  rootPath: string;
}

export interface LicenseClaims {
  schemaVersion: number;
  licenseId: string;
  tenantId: string;
  customerName: string;
  edition: string;
  issuedAt: string;
  notBefore?: string | null;
  expiresAt?: string | null;
  entitlements: string[];
  limits: Record<string, number>;
}

export interface TenantLicenseStatus {
  tenantId: string;
  tenantName: string;
  tenantSlug: string;
  status: string;
  processingAllowed: boolean;
  licenseRecordId?: string | null;
  claims?: LicenseClaims | null;
  reasonCode: string;
}

export interface LicensingOverview {
  enforcementEnabled: boolean;
  verificationKeyConfigured: boolean;
  tenants: TenantLicenseStatus[];
}

export interface LicenseHistoryItem {
  recordId: string;
  licenseId: string;
  installedAt: string;
  installedBy: string;
  active: boolean;
  status: string;
  claims?: LicenseClaims | null;
  reasonCode: string;
  verificationEvidence?: 'CURRENT_KEY' | 'VERIFIED_AT_INSTALL' | 'UNAVAILABLE' | string;
}

export interface CurrentUser {
  username: string;
  roles: string[];
  tenantName: string;
  tenantSlug: string;
  authenticationMode: 'LOCAL' | 'OIDC' | string;
  capabilities: string[];
}

export interface TechnicalIncidentEvent {
  eventType: string;
  attemptNumber?: number | null;
  scope?: string | null;
  reasonCode?: string | null;
  retryable?: boolean | null;
  correlationId: string;
  occurredAt: string;
  safeMetadata: Record<string, string>;
}


export interface SupportIncidentSummary {
  incidentId: string;
  tenantId: string;
  packageId: string;
  documentId?: string | null;
  primaryIdentifierLabel?: string | null;
  primaryIdentifierValue?: string | null;
  stage: string;
  component: string;
  reasonCode: string;
  status: string;
  businessOutcome?: string | null;
  businessContinuityUsed: boolean;
  retryable: boolean;
  attemptCount: number;
  correlationId: string;
  businessImpact: 'BLOCKED' | 'CONTINUED' | 'COMPLETED' | string;
  firstObservedAt: string;
  lastObservedAt: string;
  resolvedAt?: string | null;
}

export interface SupportIncidentSearchPage {
  items: SupportIncidentSummary[];
  total: number;
  page: number;
  size: number;
}

export interface SupportIncidentDetail {
  incidentId: string;
  tenantId: string;
  packageId: string;
  documentId?: string | null;
  packageStatus?: string | null;
  primaryIdentifierLabel?: string | null;
  primaryIdentifierValue?: string | null;
  status: string;
  businessOutcome?: string | null;
  businessImpact: string;
  businessContinuityUsed: boolean;
  stage: string;
  component: string;
  failureCategory: string;
  reasonCode: string;
  retryable: boolean;
  attemptCount: number;
  correlationId: string;
  safeSummary: string;
  artifactAvailability?: 'PURGED' | 'PENDING_PURGE' | 'RETAINED_UNTIL_POLICY_EXPIRY' | 'UNKNOWN' | string;
  technicalAttentionRetainedUntil?: string | null;
  artifactsPurgedAt?: string | null;
  processingFingerprint: Record<string, string>;
  safeMetadata: Record<string, string>;
  firstObservedAt: string;
  lastObservedAt: string;
  resolvedAt?: string | null;
  events: TechnicalIncidentEvent[];
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
  incidentId?: string | null;
  incidentStatus?: string | null;
  businessContinuityUsed?: boolean;
  businessOutcome?: string | null;
  processingFingerprint?: Record<string, string>;
  incidentEvents?: TechnicalIncidentEvent[];
  operatorDiagnostics?: {
    dependency: string;
    internalErrorCode: string;
    attempts: number;
    environment: string;
  } | null;
}

export interface EffectiveBranding {
  tenantId?: string | null;
  applicationName: string;
  shortName: string;
  organizationName?: string | null;
  pageTitle: string;
  accentColor: string;
  secondaryColor: string;
  primaryLogoDataUrl?: string | null;
  compactLogoDataUrl?: string | null;
  faviconDataUrl?: string | null;
}

export interface BrandingConfiguration {
  id: string;
  tenantId?: string | null;
  applicationName?: string | null;
  shortName?: string | null;
  organizationName?: string | null;
  pageTitle?: string | null;
  accentColor?: string | null;
  secondaryColor?: string | null;
  primaryLogoDataUrl?: string | null;
  compactLogoDataUrl?: string | null;
  faviconDataUrl?: string | null;
  updatedBy: string;
  updatedAt: string;
}

export interface TenantBrandingSummary {
  tenantId: string;
  tenantName: string;
  tenantSlug: string;
  customBranding: boolean;
}

export interface TenantBrandingDetail {
  tenantId: string;
  customBranding: boolean;
  configuration?: BrandingConfiguration | null;
}

export interface BrandingOverview {
  deployment?: BrandingConfiguration | null;
  tenants: TenantBrandingSummary[];
}

export interface BrandingAssetUpdate {
  dataUrl?: string | null;
  clear?: boolean;
}

export interface BrandingUpdateRequest {
  applicationName?: string | null;
  shortName?: string | null;
  organizationName?: string | null;
  pageTitle?: string | null;
  accentColor?: string | null;
  secondaryColor?: string | null;
  primaryLogo?: BrandingAssetUpdate | null;
  compactLogo?: BrandingAssetUpdate | null;
  favicon?: BrandingAssetUpdate | null;
}
