import { API_BASE_URL, defaultHeaders, baseHeaders } from './config';
import { expireOidcSession, isOidcSecurityMode } from './auth';
import type {
  PackageSummary,
  PackageDetail,
  CreatePackageRequest,
  PackageStatusResult,
  DocumentUploadResult,
  ReviewTaskResult,
  AuditRecordResult,
  ApiError,
  FocusedReviewQueue,
  ReviewPagePreview,
  ReviewBoundingBox,
  ReviewResolutionResult,
  ManualFallbackDetail,
  ManualFallbackPagePreview,
  ManualFallbackRegion,
  IdentifierDefinition,
  BusinessAdminConfiguration,
  BusinessAdminIdentifierDraft,
  DashboardAdminColumnConfiguration,
  SensitiveDataPolicyConfiguration,
  LifecyclePolicyConfiguration,
  DocumentIntelligenceMode,
  ReportingConfiguration,
  PackageLifecycleSummary,
  OperationsDashboard,
  OperationalReports,
  DashboardStage,
  PlatformAdminOverview,
  ProductTelemetryStatus,
  CurrentUser,
  TechnicalExceptionDetail,
  SupportIncidentSearchPage,
  SupportIncidentDetail,
  IntegrationConnection,
  IntegrationConnectionRequest,
  PollingConnectionStatus,
  PollingRun,
  SdvConnectionTestResult,
  RoutingRule,
  RoutingRuleRequest,
  RoutingDestinationOption,
  DerivedDelivery,
  ProcessingStorageOverview,
  ProcessingStorageProvider,
  CreateProcessingStorageProviderRequest,
  LicensingOverview,
  TenantLicenseStatus,
  LicenseHistoryItem,
  EffectiveBranding,
  BrandingOverview,
  BrandingConfiguration,
  BrandingUpdateRequest,
  TenantBrandingDetail,
  EnterpriseIdentityOverview,
  EnterpriseIdentityDraftRequest,
} from '../types';

class ApiClient {
  private baseUrl = API_BASE_URL;

  private async request<T>(path: string, options: RequestInit = {}): Promise<T> {
    const url = `${this.baseUrl}${path}`;
    const headers = { ...(await defaultHeaders()), ...options.headers };

    if (import.meta.env.DEV && path === '/me') console.info('[LexaVault OIDC] Calling authenticated GET /api/v1/me. Bearer token value is intentionally not logged.');
    const response = await fetch(url, { ...options, headers });
    if (import.meta.env.DEV && path === '/me') console.info('[LexaVault OIDC] GET /api/v1/me response.', { status: response.status, statusText: response.statusText });

    if (!response.ok) {
      if (response.status === 401 && isOidcSecurityMode()) {
        expireOidcSession();
      }
      let error: ApiError = { message: 'Request failed' };
      try {
        error = await response.json();
      } catch {
        error.message = `HTTP ${response.status}: ${response.statusText}`;
      }
      if (import.meta.env.DEV && path === '/me') {
        console.error('[LexaVault OIDC] GET /api/v1/me rejected.', { status: response.status, statusText: response.statusText, error });
        throw { ...error, httpStatus: response.status, httpStatusText: response.statusText };
      }
      throw error;
    }

    const contentType = response.headers.get('content-type');
    if (contentType && contentType.includes('application/json')) {
      return response.json();
    }
    return response as unknown as T;
  }

  async getCurrentUser(): Promise<CurrentUser> {
    return this.request('/me');
  }

  async getEnterpriseIdentityOverview(): Promise<EnterpriseIdentityOverview> {
    return this.request('/admin/enterprise-identity');
  }

  async saveEnterpriseIdentityDraft(body: EnterpriseIdentityDraftRequest): Promise<EnterpriseIdentityOverview> {
    return this.request('/admin/enterprise-identity/draft', { method: 'PUT', body: JSON.stringify(body) });
  }

  async testEnterpriseIdentityDraft(): Promise<EnterpriseIdentityOverview> {
    return this.request('/admin/enterprise-identity/draft/test', { method: 'POST' });
  }

  async activateEnterpriseIdentityDraft(connectionId: string, expectedConfigurationVersion: number): Promise<EnterpriseIdentityOverview> {
    return this.request('/admin/enterprise-identity/draft/activate', { method: 'POST', body: JSON.stringify({ connectionId, expectedConfigurationVersion, confirmation: 'ACTIVATE' }) });
  }

  async rollbackEnterpriseIdentity(connectionId: string, expectedConfigurationVersion: number): Promise<EnterpriseIdentityOverview> {
    return this.request('/admin/enterprise-identity/rollback', { method: 'POST', body: JSON.stringify({ connectionId, expectedConfigurationVersion, confirmation: 'ROLLBACK' }) });
  }

  // --- UI branding ---

  async getCurrentBranding(): Promise<EffectiveBranding> {
    return this.request('/branding/current');
  }

  async getBrandingOverview(): Promise<BrandingOverview> {
    return this.request('/platform/branding');
  }

  async getTenantBranding(tenantId: string): Promise<TenantBrandingDetail> {
    return this.request(`/platform/branding/tenants/${tenantId}`);
  }

  async updateDeploymentBranding(body: BrandingUpdateRequest): Promise<BrandingConfiguration> {
    return this.request('/platform/branding/deployment', { method: 'PUT', body: JSON.stringify(body) });
  }

  async updateTenantBranding(tenantId: string, body: BrandingUpdateRequest): Promise<BrandingConfiguration> {
    return this.request(`/platform/branding/tenants/${tenantId}`, { method: 'PUT', body: JSON.stringify(body) });
  }

  async clearTenantBranding(tenantId: string): Promise<void> {
    await this.request(`/platform/branding/tenants/${tenantId}`, { method: 'DELETE' });
  }


  // --- Package intake ---

  async listIdentifierDefinitions(): Promise<IdentifierDefinition[]> {
    return this.request('/intake/identifier-definitions');
  }


  // --- Business Admin ---

  async getBusinessAdminConfiguration(): Promise<BusinessAdminConfiguration> {
    return this.request('/business-admin/configuration');
  }

  async updateBusinessIdentifiers(identifiers: BusinessAdminIdentifierDraft[]): Promise<BusinessAdminConfiguration> {
    return this.request('/business-admin/identifiers', {
      method: 'PUT',
      body: JSON.stringify({ identifiers }),
    });
  }

  async updateDashboardColumns(columns: DashboardAdminColumnConfiguration[]): Promise<BusinessAdminConfiguration> {
    return this.request('/business-admin/dashboard-columns', {
      method: 'PUT',
      body: JSON.stringify({
        columns: columns.map(({ key, allowed, defaultVisible }) => ({ key, allowed, defaultVisible })),
      }),
    });
  }

  async updateSensitiveDataPolicy(policy: SensitiveDataPolicyConfiguration): Promise<BusinessAdminConfiguration> {
    return this.request('/business-admin/sensitive-data-policy', {
      method: 'PUT',
      body: JSON.stringify(policy),
    });
  }

  async updateLifecyclePolicy(policy: Pick<LifecyclePolicyConfiguration, 'originalRetentionHours' | 'redactedCacheHours' | 'neverExportedHoldHours' | 'finalApprovalRequired'>): Promise<BusinessAdminConfiguration> {
    return this.request('/business-admin/lifecycle-policy', {
      method: 'PUT',
      body: JSON.stringify(policy),
    });
  }

  async updateReportingConfiguration(configuration: ReportingConfiguration): Promise<BusinessAdminConfiguration> {
    return this.request('/business-admin/reporting-configuration', {
      method: 'PUT',
      body: JSON.stringify({
        enabledDimensionDefinitionIds: configuration.dimensions.filter((dimension) => dimension.enabled).map((dimension) => dimension.definitionId),
        deliveryTargetMinutes: configuration.deliveryTargetMinutes,
        deliveryTargetPercent: configuration.deliveryTargetPercent,
        straightThroughTargetPercent: configuration.straightThroughTargetPercent,
        reviewWaitTargetMinutes: configuration.reviewWaitTargetMinutes,
      }),
    });
  }

  async updateDocumentIntelligence(mode: DocumentIntelligenceMode, activeBundleVersion: string): Promise<BusinessAdminConfiguration> {
    return this.request('/business-admin/document-intelligence', {
      method: 'PUT',
      body: JSON.stringify({ mode, activeBundleVersion }),
    });
  }

  async rollbackDocumentIntelligence(): Promise<BusinessAdminConfiguration> {
    return this.request('/business-admin/document-intelligence/rollback', { method: 'POST' });
  }

  async markDocumentIntelligenceLastKnownGood(): Promise<BusinessAdminConfiguration> {
    return this.request('/business-admin/document-intelligence/last-known-good', { method: 'POST' });
  }


  // --- Shared operational dashboard + reports ---

  async getOperationsDashboard(params: {
    stage?: DashboardStage;
    search?: string;
    offset?: number;
    limit?: number;
    sort?: 'receivedAt' | 'updatedAt' | 'stage';
    direction?: 'asc' | 'desc';
  } = {}): Promise<OperationsDashboard> {
    const query = new URLSearchParams();
    if (params.stage) query.set('stage', params.stage);
    if (params.search) query.set('search', params.search);
    query.set('offset', String(params.offset ?? 0));
    query.set('limit', String(params.limit ?? 25));
    query.set('sort', params.sort ?? 'receivedAt');
    query.set('direction', params.direction ?? 'desc');
    return this.request(`/operations/dashboard?${query.toString()}`);
  }

  async getOperationalReports(params: { from: string; to: string; source: string; outcome: string }): Promise<OperationalReports> {
    const query = new URLSearchParams(params);
    return this.request(`/operations/reports?${query.toString()}`);
  }

  async exportOperationalReports(params: { from: string; to: string; source: string; outcome: string }): Promise<Blob> {
    const query = new URLSearchParams(params);
    const response = await this.request<Response>(`/operations/reports/export?${query.toString()}`);
    return response.blob();
  }

  // --- Tenant integration connections ---

  async listIntegrationConnections(): Promise<IntegrationConnection[]> {
    return this.request('/integrations');
  }

  async createIntegrationConnection(body: IntegrationConnectionRequest): Promise<IntegrationConnection> {
    return this.request('/integrations', { method: 'POST', body: JSON.stringify(body) });
  }

  async updateIntegrationConnection(connectionId: string, body: IntegrationConnectionRequest): Promise<IntegrationConnection> {
    return this.request(`/integrations/${connectionId}`, { method: 'PUT', body: JSON.stringify(body) });
  }

  async setIntegrationConnectionEnabled(connectionId: string, enabled: boolean): Promise<IntegrationConnection> {
    return this.request(`/integrations/${connectionId}/${enabled ? 'enable' : 'disable'}`, { method: 'POST' });
  }

  async getPollingConnectionStatus(connectionId: string): Promise<PollingConnectionStatus> {
    return this.request(`/integrations/${connectionId}/polling`);
  }

  async runPollingNow(connectionId: string): Promise<PollingRun> {
    return this.request(`/integrations/${connectionId}/polling/run-now`, { method: 'POST' });
  }

  async testSdvConnection(connectionId: string): Promise<SdvConnectionTestResult> {
    return this.request(`/integrations/${connectionId}/sdv/test-connection`, { method: 'POST' });
  }

  async testDerivedConnection(connectionId: string): Promise<{ connectionId: string; healthStatus: string; configurationVersion: number }> {
    return this.request(`/integrations/${connectionId}/derived/test-connection`, { method: 'POST' });
  }

  async listRoutingRules(): Promise<RoutingRule[]> {
    return this.request('/business-admin/routing-rules');
  }

  async listRoutingDestinations(): Promise<RoutingDestinationOption[]> {
    return this.request('/business-admin/routing-rules/destinations');
  }

  async createRoutingRule(body: RoutingRuleRequest): Promise<RoutingRule> {
    return this.request('/business-admin/routing-rules', { method: 'POST', body: JSON.stringify(body) });
  }

  async updateRoutingRule(ruleId: string, body: RoutingRuleRequest): Promise<RoutingRule> {
    return this.request(`/business-admin/routing-rules/${ruleId}`, { method: 'PUT', body: JSON.stringify(body) });
  }

  async deleteRoutingRule(ruleId: string): Promise<void> {
    await this.request(`/business-admin/routing-rules/${ruleId}`, { method: 'DELETE' });
  }

  async listDerivedDeliveries(packageId: string): Promise<DerivedDelivery[]> {
    return this.request(`/packages/${packageId}/derived-deliveries`);
  }

  async sendDerivedDelivery(packageId: string, deliveryId: string): Promise<DerivedDelivery> {
    return this.request(`/packages/${packageId}/derived-deliveries/${deliveryId}/send`, { method: 'POST' });
  }

  async listDerivedDeliveryAttention(): Promise<DerivedDelivery[]> {
    return this.request('/derived-deliveries/attention');
  }

  async retryDerivedDelivery(deliveryId: string): Promise<DerivedDelivery> {
    return this.request(`/derived-deliveries/${deliveryId}/technical-retry`, { method: 'POST' });
  }

  // --- IT / Platform Admin (Unit 24) ---

  async getPlatformAdminOverview(): Promise<PlatformAdminOverview> {
    return this.request('/platform-admin/overview');
  }

  async runPlatformHealthCheck(): Promise<PlatformAdminOverview> {
    return this.request('/platform-admin/health-check', { method: 'POST' });
  }

  async getProductTelemetryStatus(): Promise<ProductTelemetryStatus> {
    return this.request('/platform/product-telemetry/status');
  }

  async scanPlatformWatchedFolder(): Promise<{ discovered: number; waitingForTrigger: number; accepted: number; rejected: number; duplicates: number }> {
    return this.request('/platform-admin/watched-folder/scan', { method: 'POST' });
  }

  // --- Platform-managed processing storage ---

  async getProcessingStorageOverview(): Promise<ProcessingStorageOverview> {
    return this.request('/platform/processing-storage');
  }

  async createProcessingStorageProvider(body: CreateProcessingStorageProviderRequest): Promise<ProcessingStorageProvider> {
    return this.request('/platform/processing-storage/providers', { method: 'POST', body: JSON.stringify(body) });
  }

  async testProcessingStorageProvider(providerId: string): Promise<ProcessingStorageOverview> {
    return this.request(`/platform/processing-storage/providers/${providerId}/test`, { method: 'POST' });
  }

  async activateProcessingStorageDefault(providerId: string): Promise<ProcessingStorageProvider> {
    return this.request(`/platform/processing-storage/providers/${providerId}/activate-default`, { method: 'POST' });
  }

  async activateProcessingStorageTenant(providerId: string, tenantId: string): Promise<ProcessingStorageProvider> {
    return this.request(`/platform/processing-storage/providers/${providerId}/activate-tenant/${tenantId}`, { method: 'POST' });
  }

  async clearProcessingStorageTenantOverride(tenantId: string): Promise<void> {
    await this.request(`/platform/processing-storage/tenant-overrides/${tenantId}`, { method: 'DELETE' });
  }

  async retireProcessingStorageProvider(providerId: string): Promise<ProcessingStorageProvider> {
    return this.request(`/platform/processing-storage/providers/${providerId}/retire`, { method: 'POST' });
  }

  // --- Tenant licensing ---

  async getLicensingOverview(): Promise<LicensingOverview> {
    return this.request('/platform/licensing');
  }

  async getTenantLicenseStatus(tenantId: string): Promise<TenantLicenseStatus> {
    return this.request(`/platform/licensing/tenants/${tenantId}`);
  }

  async installTenantLicense(tenantId: string, licenseToken: string): Promise<TenantLicenseStatus> {
    return this.request(`/platform/licensing/tenants/${tenantId}/licenses`, {
      method: 'POST',
      body: JSON.stringify({ licenseToken }),
    });
  }

  async getTenantLicenseHistory(tenantId: string): Promise<LicenseHistoryItem[]> {
    return this.request(`/platform/licensing/tenants/${tenantId}/history`);
  }

  async getTechnicalException(packageId: string): Promise<TechnicalExceptionDetail> {
    return this.request(`/technical-exceptions/${packageId}`);
  }

  async retryTechnicalException(packageId: string): Promise<{ packageId: string; status: string }> {
    return this.request(`/technical-exceptions/${packageId}/retry`, { method: 'POST' });
  }

  async searchSupportIncidents(params: {
    businessIdentifier?: string;
    incidentId?: string;
    correlationId?: string;
    packageId?: string;
    documentId?: string;
    stage?: string;
    reasonCode?: string;
    status?: string;
    businessOutcome?: string;
    businessContinuityUsed?: boolean;
    retryable?: boolean;
    from?: string;
    to?: string;
    tenantId?: string;
    page?: number;
    size?: number;
  } = {}): Promise<SupportIncidentSearchPage> {
    const query = new URLSearchParams();
    Object.entries(params).forEach(([key, value]) => {
      if (value !== undefined && value !== null && value !== '') query.set(key, String(value));
    });
    const suffix = query.toString() ? `?${query.toString()}` : '';
    return this.request(`/support/incidents${suffix}`);
  }

  async getSupportIncident(incidentId: string, tenantId?: string): Promise<SupportIncidentDetail> {
    const suffix = tenantId ? `?tenantId=${encodeURIComponent(tenantId)}` : '';
    return this.request(`/support/incidents/${incidentId}${suffix}`);
  }

  async actOnSupportIncident(incidentId: string, body: { action: string; holdDays?: number; resolutionCode?: string }, tenantId?: string): Promise<{ incidentId: string; packageId: string; action: string; status: string }> {
    const suffix = tenantId ? `?tenantId=${encodeURIComponent(tenantId)}` : '';
    return this.request(`/support/incidents/${incidentId}/actions${suffix}`, {
      method: 'POST',
      body: JSON.stringify(body),
    });
  }

  async listPackages(): Promise<PackageSummary[]> {
    return this.request('/packages');
  }

  async getPackage(packageId: string): Promise<PackageDetail> {
    const detail = await this.request<PackageDetail>(`/packages/${packageId}`);
    return {
      ...detail,
      // The detail endpoint historically omitted documentCount even though the
      // dashboard summary includes it. Derive it defensively so the UI never
      // renders "Documents (undefined)" while remaining compatible with a
      // future API response that includes the field explicitly.
      documentCount: Number.isFinite(detail.documentCount) ? detail.documentCount : (detail.documents?.length ?? 0),
    };
  }

  async getPackageLifecycle(packageId: string): Promise<PackageLifecycleSummary> {
    return this.request(`/packages/${packageId}/lifecycle`);
  }

  async reprocessPackage(packageId: string): Promise<{ packageId: string; status: string; nextExportCycle: number }> {
    return this.request(`/packages/${packageId}/reprocess`, { method: 'POST' });
  }

  async approvePackageDelivery(packageId: string): Promise<{ packageId: string; status: string }> {
    return this.request(`/packages/${packageId}/approve-delivery`, { method: 'POST' });
  }

  async hidePackageFromDashboard(packageId: string): Promise<{ packageId: string; hiddenFromDashboard: boolean; hiddenAt: string }> {
    return this.request(`/packages/${packageId}/dashboard-hide`, { method: 'POST' });
  }

  async extendTechnicalHold(packageId: string, holdHoursFromNow: number): Promise<PackageLifecycleSummary> {
    return this.request(`/packages/${packageId}/technical-hold`, {
      method: 'POST',
      body: JSON.stringify({ holdHoursFromNow }),
    });
  }

  async createPackage(data: CreatePackageRequest): Promise<PackageStatusResult> {
    return this.request('/packages', {
      method: 'POST',
      body: JSON.stringify(data),
    });
  }

  async uploadDocument(packageId: string, file: File): Promise<DocumentUploadResult> {
    const formData = new FormData();
    formData.append('file', file);

    const url = `${this.baseUrl}/packages/${packageId}/documents`;
    const response = await fetch(url, {
      method: 'POST',
      headers: await baseHeaders(),
      body: formData,
    });

    if (!response.ok) {
      let error: ApiError = { message: 'Upload failed' };
      try {
        error = await response.json();
      } catch {
        error.message = `HTTP ${response.status}: ${response.statusText}`;
      }
      throw error;
    }
    return response.json();
  }

  async submitPackage(packageId: string): Promise<PackageStatusResult> {
    return this.request(`/packages/${packageId}/submit`, { method: 'POST' });
  }

  async triggerProcessing(packageId: string): Promise<PackageStatusResult> {
    return this.request(`/packages/${packageId}/process`, { method: 'POST' });
  }

  async finalizePackage(packageId: string): Promise<PackageStatusResult> {
    return this.request(`/packages/${packageId}/finalize`, { method: 'POST' });
  }

  // --- Focused Manual Review ---

  async listReviewItems(limit = 50, packageId?: string): Promise<FocusedReviewQueue> {
    const params = new URLSearchParams({ limit: String(limit) });
    if (packageId) params.set('packageId', packageId);
    return this.request(`/review-items?${params.toString()}`);
  }

  async getReviewPagePreview(findingId: string): Promise<ReviewPagePreview> {
    const url = `${this.baseUrl}/review-items/${findingId}/page-preview`;
    const response = await fetch(url, {
      headers: { ...(await baseHeaders()), Accept: 'image/png, application/json' },
      cache: 'no-store',
    });
    if (!response.ok) {
      const raw = await response.text();
      let parsed: ApiError | null = null;
      if (raw) {
        try {
          parsed = JSON.parse(raw) as ApiError;
        } catch {
          // Some container/security failures may return a non-JSON 4xx body.
        }
      }
      if (parsed?.message) throw parsed;
      throw { message: `HTTP ${response.status}: ${response.statusText}` } as ApiError;
    }
    const pageNumber = Number(response.headers.get('X-Review-Page-Number'));
    return {
      blob: await response.blob(),
      pageNumber: Number.isFinite(pageNumber) && pageNumber > 0 ? pageNumber : 0,
    };
  }

  async confirmReviewItem(
    findingId: string,
    boundingBox?: ReviewBoundingBox,
    reason = 'Reviewer confirmed sensitive content'
  ): Promise<ReviewResolutionResult> {
    return this.request(`/review-items/${findingId}/confirm`, {
      method: 'POST',
      body: JSON.stringify({ reason, boundingBox: boundingBox || null }),
    });
  }

  async dismissReviewItem(
    findingId: string,
    reason = 'Reviewer marked item as not sensitive'
  ): Promise<ReviewResolutionResult> {
    return this.request(`/review-items/${findingId}/dismiss`, {
      method: 'POST',
      body: JSON.stringify({ reason }),
    });
  }

  // --- Business continuity manual fallback ---

  async getManualFallbackEligibility(packageId: string): Promise<{ eligible: boolean; reason: string }> {
    return this.request(`/packages/${packageId}/manual-fallback/eligibility`);
  }

  async startManualFallback(packageId: string): Promise<ManualFallbackDetail> {
    return this.request(`/packages/${packageId}/manual-fallback/start`, { method: 'POST' });
  }

  async getManualFallback(packageId: string): Promise<ManualFallbackDetail> {
    return this.request(`/packages/${packageId}/manual-fallback`);
  }

  async getManualFallbackPagePreview(
    packageId: string,
    documentId: string,
    pageNumber: number
  ): Promise<ManualFallbackPagePreview> {
    const url = `${this.baseUrl}/packages/${packageId}/manual-fallback/documents/${documentId}/pages/${pageNumber}/preview`;
    const response = await fetch(url, {
      headers: { ...(await baseHeaders()), Accept: 'image/png, application/json' },
      cache: 'no-store',
    });
    if (!response.ok) {
      const raw = await response.text();
      let parsed: ApiError | null = null;
      if (raw) {
        try { parsed = JSON.parse(raw) as ApiError; } catch { /* non-JSON security/container response */ }
      }
      if (parsed?.message) throw parsed;
      throw { message: `HTTP ${response.status}: ${response.statusText}` } as ApiError;
    }
    const resolvedPage = Number(response.headers.get('X-Manual-Page-Number'));
    return {
      blob: await response.blob(),
      pageNumber: Number.isFinite(resolvedPage) && resolvedPage > 0 ? resolvedPage : pageNumber,
    };
  }

  async addManualFallbackRegion(
    packageId: string,
    documentId: string,
    pageNumber: number,
    entityType: string,
    boundingBox: ReviewBoundingBox
  ): Promise<ManualFallbackRegion> {
    return this.request(`/packages/${packageId}/manual-fallback/regions`, {
      method: 'POST',
      body: JSON.stringify({ documentId, pageNumber, entityType, boundingBox }),
    });
  }

  async removeManualFallbackRegion(packageId: string, findingId: string): Promise<void> {
    await this.request(`/packages/${packageId}/manual-fallback/regions/${findingId}`, { method: 'DELETE' });
  }

  async submitManualFallback(packageId: string, reviewAttested: boolean): Promise<PackageStatusResult> {
    return this.request(`/packages/${packageId}/manual-fallback/submit`, {
      method: 'POST',
      body: JSON.stringify({ reviewAttested }),
    });
  }

  // --- Legacy review endpoints (not used by the V1 workbench) ---

  async createReviewTask(packageId: string): Promise<ReviewTaskResult> {
    return this.request('/review-tasks', {
      method: 'POST',
      body: JSON.stringify({ packageId }),
    });
  }

  async assignReviewTask(taskId: string): Promise<ReviewTaskResult> {
    return this.request(`/review-tasks/${taskId}/assign`, {
      method: 'POST',
      body: JSON.stringify({}),
    });
  }

  async startReviewTask(taskId: string): Promise<ReviewTaskResult> {
    return this.request(`/review-tasks/${taskId}/start`, { method: 'POST' });
  }

  async completeReviewTask(taskId: string, comment?: string): Promise<ReviewTaskResult> {
    return this.request(`/review-tasks/${taskId}/complete`, {
      method: 'POST',
      body: JSON.stringify({ comment: comment || '' }),
    });
  }

  async confirmFinding(findingId: string, reviewTaskId: string, reason?: string): Promise<{ findingId: string; status: string }> {
    return this.request(`/findings/${findingId}/confirm`, {
      method: 'POST',
      body: JSON.stringify({ reviewTaskId, reason: reason || '' }),
    });
  }

  async dismissFinding(findingId: string, reviewTaskId: string, reason?: string): Promise<{ findingId: string; status: string }> {
    return this.request(`/findings/${findingId}/dismiss`, {
      method: 'POST',
      body: JSON.stringify({ reviewTaskId, reason: reason || '' }),
    });
  }

  async updateFindingBoundingBox(
    findingId: string,
    reviewTaskId: string,
    box: { left: number; top: number; width: number; height: number }
  ): Promise<{ findingId: string; status: string }> {
    return this.request(`/findings/${findingId}/bounding-box`, {
      method: 'POST',
      body: JSON.stringify({ reviewTaskId, ...box }),
    });
  }

  // Legacy original/vault access workflow is intentionally not part of the V1 UI.

  async downloadRedactedArtifact(documentId: string): Promise<Blob> {
    const url = `${this.baseUrl}/documents/${documentId}/artifacts/redacted/content`;
    const response = await fetch(url, { headers: await baseHeaders() });
    if (!response.ok) {
      let error: ApiError = { message: 'Download failed' };
      try {
        error = await response.json();
      } catch {
        error.message = `HTTP ${response.status}: ${response.statusText}`;
      }
      throw error;
    }
    return response.blob();
  }



  // --- Audit ---

  async listAuditRecords(filters?: Record<string, string>): Promise<AuditRecordResult[]> {
    const params = new URLSearchParams();
    if (filters) {
      Object.entries(filters).forEach(([key, value]) => {
        if (value) params.append(key, value);
      });
    }
    const query = params.toString() ? `?${params.toString()}` : '';
    return this.request(`/audit-records${query}`);
  }

  async exportAuditRecords(filters: Record<string, string>, format: 'csv' | 'json'): Promise<Blob> {
    const params = new URLSearchParams({ ...filters, format });
    const response = await fetch(`${this.baseUrl}/audit-records/export?${params.toString()}`, { headers: await baseHeaders() });
    if (!response.ok) {
      let error: ApiError = { message: 'Audit export failed' };
      try { error = await response.json(); } catch { error.message = `HTTP ${response.status}: ${response.statusText}`; }
      throw error;
    }
    return response.blob();
  }
}

export const apiClient = new ApiClient();
