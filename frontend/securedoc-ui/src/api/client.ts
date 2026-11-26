import { API_BASE_URL, defaultHeaders, baseHeaders } from './config';
import { expireOidcSession, isOidcSecurityMode } from './auth';
import { selectedOidcTenant, oidcTenantContextRevision } from './tenantContext';
import { beginWrite } from './pendingWork';
import type { PolicyEstimate } from '../studio/admin/policyRules';
import type {
  CaseExport, DestinationView as ExportDestinationView, NeedsIt, ReachabilityAlert, RetentionSettings, WaitingForYou,
} from '../studio/exportRules';
import type {
  PackageSummary,
  PackageDetail,
  CreatePackageRequest,
  WatchedFolderSettings,
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
  DailyReport,
  ReportFilters,
  ReportSource,
  DashboardStage,
  PlatformAdminOverview,
  PipelineQueue,
  ProcessingCapacity,
  SourcePriorities,
  BulkSources,
  ProductTelemetryStatus,
  CurrentUser,
  TechnicalExceptionDetail,
  SupportIncidentSearchPage,
  SupportIncidentDetail,
  IntegrationConnection,
  DestinationProfile,
  IntegrationConnectionRequest,
  PollingConnectionStatus,
  PollingRun,
  DocumentStoreConnectionTestResult,
  DraftConnectionTestResult,
  RoutingRule,
  RoutingRuleRequest,
  RoutingDestinationOption,
  DerivedDelivery,
  ProcessingStorageOverview,
  ProcessingStorageProvider,
  CreateProcessingStorageProviderRequest,
  LicensingView,
  SupportStatusView,
  EffectiveBranding,
  BrandingOverview,
  BrandingConfiguration,
  BrandingUpdateRequest,
  TenantBrandingDetail,
  EnterpriseIdentityOverview,
  SignInTestAnswer,
  SignInChangeAnswer,
  GrantHistoryRow,
  EntitySlotsOverview,
  EntityChangeAnswer,
  ApprovalAnswer,
  NotificationBusinessOverview,
  NotificationEventChange,
  NotificationItOverview,
} from '../types';

/** Task 0101: the longest a page preview may take before it is shown as failed. */
const PAGE_PREVIEW_TIMEOUT_MS = 60_000;

class ApiClient {
  private baseUrl = API_BASE_URL;

  private request<T>(path: string, options: RequestInit = {}): Promise<T> {
    // Writes in flight count as in-progress work (an organization switch asks for confirmation first).
    const method = (options.method ?? 'GET').toUpperCase();
    if (method === 'GET' || method === 'HEAD') return this.send<T>(path, options);
    const endWrite = beginWrite();
    return this.send<T>(path, options).finally(endWrite);
  }

  private async send<T>(path: string, options: RequestInit = {}): Promise<T> {
    const url = `${this.baseUrl}${path}`;
    const startingTenant = selectedOidcTenant();
    const startingRevision = oidcTenantContextRevision();
    const headers = { ...(await defaultHeaders()), ...options.headers };
    // A tenant change during asynchronous token refresh must never send stale context.
    if (isOidcSecurityMode() && (startingTenant !== selectedOidcTenant() || startingRevision !== oidcTenantContextRevision())) {
      throw new Error('Tenant context changed before the request was sent');
    }

    if (import.meta.env.DEV && path === '/me') console.info('[LexaVault OIDC] Calling authenticated GET /api/v1/me. Bearer token value is intentionally not logged.');
    const response = await fetch(url, { ...options, headers });
    if (isOidcSecurityMode() && (startingTenant !== selectedOidcTenant() || startingRevision !== oidcTenantContextRevision())) {
      throw new Error('Tenant context changed while the request was in progress');
    }
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
      }
      throw { ...error, httpStatus: response.status, httpStatusText: response.statusText };
    }

    const contentType = response.headers.get('content-type');
    if (contentType && contentType.includes('application/json')) {
      const result = await response.json();
      if (isOidcSecurityMode() && (startingTenant !== selectedOidcTenant() || startingRevision !== oidcTenantContextRevision())) {
        throw new Error('Tenant context changed while the response was being read');
      }
      return result;
    }
    return response as unknown as T;
  }

  async getAvailableOidcTenants(): Promise<Array<{ tenantId: string; name: string; slug: string; roles: string[]; lastUsed?: boolean }>> {
    return this.request('/me/tenants');
  }

  async getCurrentUser(): Promise<CurrentUser> {
    return this.request('/me');
  }

  /**
   * Asks the server whether this person may enter `tenantId`, without changing the current selection.
   * The server revalidates the hint against the verified token and memberships, as on every request.
   */
  async getCurrentUserForTenant(tenantId: string): Promise<CurrentUser> {
    return this.request('/me', { headers: { 'X-Selected-Tenant-Id': tenantId } });
  }

  /**
   * The person enters an entity (task 0058): the server checks it as on every request, remembers it for the next sign-in and
   * records a switch. Without an id: the entity already entered.
   */
  async enteredEntity(tenantId?: string): Promise<void> {
    await this.request('/me/entity', { method: 'POST', ...(tenantId ? { headers: { 'X-Selected-Tenant-Id': tenantId } } : {}) });
  }

  /** Task 0058: the connection in use, the draft of a change and what going back gives. */
  async getEnterpriseIdentityOverview(): Promise<EnterpriseIdentityOverview> {
    return this.request('/admin/enterprise-identity');
  }

  /** Tests the ACTIVE sign-in connection (task 0054): no input, and nothing is stored or changed. */
  async testSignInConnection(): Promise<SignInTestAnswer> {
    return this.request('/admin/enterprise-identity/test-sign-in', { method: 'POST' });
  }

  // --- Admin, Sign-in: a change of the connection (task 0058). The server finds the draft; only its version is sent. ---
  private signInStep(path: string, body: object, method = 'POST'): Promise<SignInChangeAnswer> {
    return this.request(`/admin/enterprise-identity${path}`, { method, body: JSON.stringify(body), cache: 'no-store' });
  }

  startSignInDraft(activeVersion: number) { return this.signInStep('/draft', { expectedVersion: activeVersion }); }

  saveSignInDraft(version: number, values: { providerId: string; issuerUri: string; browserClientId: string; apiAudience: string }) {
    return this.signInStep('/draft', { expectedVersion: version, ...values }, 'PUT');
  }

  testSignInDraft(version: number, token: string | null) { return this.signInStep('/draft/test-sign-in', { expectedVersion: version, token }); }

  activateSignInDraft(version: number) { return this.signInStep('/draft/activate', { expectedVersion: version }); }

  discardSignInDraft(version: number) { return this.signInStep('/draft/discard', { expectedVersion: version }); }

  rollBackSignIn(activeVersion: number) { return this.signInStep('/rollback', { expectedVersion: activeVersion }); }

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
    // lockedItemsOn is the server's to say (task 0062), never sent.
    return this.request('/business-admin/sensitive-data-policy', {
      method: 'PUT',
      body: JSON.stringify({ ...policy, lockedItemsOn: undefined }),
    });
  }

  /** Task 0019 B2 / UI task U023: cases and findings a person would check in the last 30 days, saved vs proposed. */
  async sensitiveDataPolicyEstimate(query: Record<string, string>, signal?: AbortSignal): Promise<PolicyEstimate> {
    const params = new URLSearchParams(query);
    return this.request(`/business-admin/sensitive-data-policy/estimate?${params.toString()}`, { cache: 'no-store', signal });
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
        reviewWaitTargetPercent: configuration.reviewWaitTargetPercent,
        // Left out (undefined) when not known: the server then keeps the stored value.
        minutesPerPage: configuration.minutesPerPage,
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

  async getOperationalReports(params: ReportFilters | { from: string; to: string; source: string; outcome: string }): Promise<OperationalReports> {
    const query = new URLSearchParams({ ...params });
    return this.request(`/operations/reports?${query.toString()}`);
  }

  async exportOperationalReports(params: { from: string; to: string; source: string; outcome: string }): Promise<Blob> {
    const query = new URLSearchParams(params);
    const response = await this.request<Response>(`/operations/reports/export?${query.toString()}`);
    return response.blob();
  }

  /** Task 0081: the source systems of the Reports filter. */
  async getReportSources(): Promise<ReportSource[]> {
    return this.request('/operations/reports/sources');
  }

  async getDailyReport(params: ReportFilters): Promise<DailyReport> {
    const query = new URLSearchParams({ ...params });
    return this.request(`/operations/reports/daily?${query.toString()}`);
  }

  /** The stage view of the reader's own entity. */
  async getReportQueue(): Promise<PipelineQueue> {
    return this.request('/operations/reports/queue');
  }

  async exportReportTab(tab: 'overview' | 'daily' | 'operations', params: ReportFilters): Promise<Blob> {
    const query = new URLSearchParams({ ...params });
    const response = await this.request<Response>(`/operations/reports/${tab}/export?${query.toString()}`);
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

  async testPollingConnection(connectionId: string): Promise<PollingConnectionStatus> {
    return this.request(`/integrations/${connectionId}/polling/test-connection`, { method: 'POST' });
  }

  async runPollingNow(connectionId: string): Promise<PollingRun> {
    return this.request(`/integrations/${connectionId}/polling/run-now`, { method: 'POST' });
  }

  /** Task 0087: the destinations' published profiles in the installation's profile folder. */
  async listDestinationProfiles(): Promise<DestinationProfile[]> {
    return this.request('/integrations/destination-profiles');
  }

  /** Tests settings before they are saved. With connectionId, an empty key reference reuses that connection's stored key. Saves nothing. */
  async testDraftConnection(body: IntegrationConnectionRequest, connectionId?: string): Promise<DraftConnectionTestResult> {
    const query = connectionId ? `?connectionId=${encodeURIComponent(connectionId)}` : '';
    return this.request(`/integrations/test-draft${query}`, { method: 'POST', body: JSON.stringify(body) });
  }

  async testDocumentStoreConnection(connectionId: string): Promise<DocumentStoreConnectionTestResult> {
    return this.request(`/integrations/${connectionId}/document-store/test-connection`, { method: 'POST' });
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

  async getPipelineQueue(): Promise<PipelineQueue> {
    return this.request('/platform-admin/queue');
  }

  // Task 0065: document text.
  async caseInstructionsSetting(): Promise<{ enabled: boolean; maxCharacters: number }> {
    return this.request('/intake/case-instructions');
  }

  async caseInstructions(packageId: string): Promise<{ enabled: boolean; text: string }> {
    return this.request(`/packages/${packageId}/instructions`);
  }

  async changeCaseInstructions(packageId: string, text: string): Promise<{ enabled: boolean; text: string }> {
    return this.request(`/packages/${packageId}/instructions`, { method: 'PUT', body: JSON.stringify({ enabled: true, text }) });
  }

  async changeCasePriority(packageId: string, priority: string): Promise<{ priority: string }> {
    return this.request(`/packages/${packageId}/priority`, { method: 'PUT', body: JSON.stringify({ priority }) });
  }

  async getBulkSources(): Promise<BulkSources> {
    return this.request('/bulk-sources');
  }

  async markBulkSource(source: string, window: { from?: string; to?: string; weekends?: boolean }): Promise<BulkSources> {
    return this.request(`/bulk-sources/${encodeURIComponent(source)}`, { method: 'PUT', body: JSON.stringify(window) });
  }

  async unmarkBulkSource(source: string): Promise<BulkSources> {
    return this.request(`/bulk-sources/${encodeURIComponent(source)}`, { method: 'DELETE' });
  }

  async getSourcePriorities(): Promise<SourcePriorities> {
    return this.request('/source-priorities');
  }

  async changeSourcePriority(source: string, priority: string): Promise<SourcePriorities> {
    return this.request(`/source-priorities/${encodeURIComponent(source)}`, { method: 'PUT', body: JSON.stringify({ priority }) });
  }

  async getProcessingCapacity(): Promise<ProcessingCapacity> {
    return this.request('/platform-admin/capacity');
  }

  async changeProcessingMaximums(maximums: Record<string, number | null>): Promise<ProcessingCapacity> {
    return this.request('/platform-admin/capacity', { method: 'PUT', body: JSON.stringify({ maximums }) });
  }

  async getProductTelemetryStatus(): Promise<ProductTelemetryStatus> {
    return this.request('/platform/product-telemetry/status');
  }

  /** Task 0021: the level the installed watched folder gives every file it picks up. */
  async getWatchedFolderSettings(): Promise<WatchedFolderSettings> {
    return this.request('/platform-admin/watched-folder/settings');
  }

  async updateWatchedFolderSettings(sensitivityLevel: string): Promise<WatchedFolderSettings> {
    return this.request('/platform-admin/watched-folder/settings', { method: 'PUT', body: JSON.stringify({ sensitivityLevel }) });
  }

  /** Task 0023: outcome says whether this server checked the folder (SCANNED) or why not. */
  async scanPlatformWatchedFolder(): Promise<{ discovered: number; waitingForTrigger: number; accepted: number; rejected: number; duplicates: number; outcome?: string | null }> {
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

  // --- Support, Status (task 0057) ---

  async getSupportStatus(): Promise<SupportStatusView> {
    return this.request('/support/status');
  }

  /** The diagnostic bundle (IT Admin): a zip without document data or secrets. */
  async downloadDiagnosticBundle(): Promise<Blob> {
    const response = (await this.request<unknown>('/support/diagnostic-bundle')) as Response;
    return response.blob();
  }

  // --- Licensing (task 0057): read only ---

  // --- Admin, Entities (task 0058) ---
  async getEntities(): Promise<EntitySlotsOverview> {
    return this.request('/admin/entities');
  }

  async activateEntity(slotId: string, name: string): Promise<EntityChangeAnswer> {
    return this.request(`/admin/entities/${encodeURIComponent(slotId)}/activate`, { method: 'POST', body: JSON.stringify({ name }) });
  }

  async renameEntity(slotId: string, name: string): Promise<EntityChangeAnswer> {
    return this.request(`/admin/entities/${encodeURIComponent(slotId)}/name`, { method: 'PUT', body: JSON.stringify({ name }) });
  }

  async suspendEntity(slotId: string): Promise<EntityChangeAnswer> {
    return this.request(`/admin/entities/${encodeURIComponent(slotId)}/suspend`, { method: 'POST' });
  }

  async resumeEntity(slotId: string): Promise<EntityChangeAnswer> {
    return this.request(`/admin/entities/${encodeURIComponent(slotId)}/resume`, { method: 'POST' });
  }

  async setEntityTimeZone(slotId: string, timeZone: string | null): Promise<EntityChangeAnswer> {
    return this.request(`/admin/entities/${encodeURIComponent(slotId)}/time-zone`, { method: 'PUT', body: JSON.stringify({ timeZone }) });
  }

  // --- Admin, Notifications, and approval links (task 0059). A token goes in the body, never in an address. ---
  async getNotificationsIt(): Promise<NotificationItOverview> {
    return this.request('/admin/notifications/it');
  }

  async saveNotificationMail(body: Record<string, unknown>): Promise<NotificationItOverview> {
    return this.request('/admin/notifications/it/mail', { method: 'PUT', body: JSON.stringify(body) });
  }

  async sendNotificationTest(): Promise<void> {
    await this.request('/admin/notifications/it/test', { method: 'POST' });
  }

  async getNotificationsBusiness(): Promise<NotificationBusinessOverview> {
    return this.request('/admin/notifications/business');
  }

  async saveNotificationEvent(part: 'it' | 'business', id: string, body: NotificationEventChange): Promise<NotificationItOverview | NotificationBusinessOverview> {
    return this.request(`/admin/notifications/${part}/events/${encodeURIComponent(id)}`, { method: 'PUT', body: JSON.stringify(body) });
  }

  async openApprovalLink(token: string): Promise<ApprovalAnswer> {
    return this.request('/approvals/link/open', { method: 'POST', body: JSON.stringify({ token }), cache: 'no-store' });
  }

  async decideApprovalLink(token: string, action: 'APPROVE' | 'DECLINE', reason: string | null): Promise<ApprovalAnswer> {
    return this.request('/approvals/link/decide', { method: 'POST', body: JSON.stringify({ token, action, reason }), cache: 'no-store' });
  }

  async getLicensing(): Promise<LicensingView> {
    return this.request('/licensing');
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

  // --- Export jobs (tasks 0027/0028 API, task 0029 screens) ---

  async getWaitingForYou(): Promise<WaitingForYou> {
    return this.request('/export-jobs/waiting-for-you');
  }

  async resendExportJobs(jobIds: string[]): Promise<Array<{ jobId: string; status: string | null; lvCode: string | null; saved: boolean }>> {
    return this.request('/export-jobs/resend', { method: 'POST', body: JSON.stringify({ jobIds }) });
  }

  async resendAllExportJobs(destinationId: string): Promise<{ destinationId: string; queued: number; jobIds: string[] }> {
    return this.request('/export-jobs/resend-all', { method: 'POST', body: JSON.stringify({ destinationId }) });
  }

  async getCaseExport(packageId: string): Promise<CaseExport> {
    return this.request(`/packages/${packageId}/export-jobs`);
  }

  async getExportNeedsIt(): Promise<NeedsIt> {
    return this.request('/support/export-jobs/needs-it');
  }

  async getExportDestinations(): Promise<ExportDestinationView[]> {
    return this.request('/support/export-destinations');
  }

  async getExportAlert(): Promise<ReachabilityAlert> {
    return this.request('/support/export-destinations/alert');
  }

  async retryExportGroup(destinationId: string | null, code: string): Promise<Array<{ jobId: string; status: string | null }>> {
    return this.request('/support/export-jobs/retry-group', { method: 'POST', body: JSON.stringify({ destinationId, code }) });
  }

  async setExportDestinationWorking(destinationId: string): Promise<{ destinationId: string; state: string }> {
    return this.request(`/support/export-destinations/${destinationId}/working`, { method: 'POST' });
  }

  async checkExportDestinationNow(destinationId: string): Promise<{ destinationId: string; passed: boolean; resumed: boolean; state: string }> {
    return this.request(`/support/export-destinations/${destinationId}/check`, { method: 'POST' });
  }

  async getExportRetention(): Promise<RetentionSettings> {
    return this.request('/business-admin/export-retention');
  }

  async updateExportRetention(body: { masterHours: number | null; splitDestinations: Array<{ destinationId: string; hours: number | null }> }): Promise<RetentionSettings> {
    return this.request('/business-admin/export-retention', { method: 'PUT', body: JSON.stringify(body) });
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

  /** Task 0076: whether bulk approval is on, and which of these cases this person reviewed (they can't be selected). */
  async bulkApprovalOffer(packageIds: readonly string[]): Promise<{ enabled: boolean; maximum: number; reviewedByYou: string[] }> {
    return this.request('/bulk-approval/offer', { method: 'POST', body: JSON.stringify({ packageIds }) });
  }

  /** Task 0076: each case is approved as its own action; one that fails has its reason. */
  async bulkApprove(packageIds: readonly string[]): Promise<{ packageId: string; approved: boolean; reasonCode?: string | null }[]> {
    return this.request('/bulk-approval', { method: 'POST', body: JSON.stringify({ packageIds }) });
  }

  /** Task 0071: ends the case as Rejected. The reason is an LV code of the fixed list. */
  async rejectCase(packageId: string, step: 'review' | 'approval', reasonCode: string): Promise<{ packageId: string; status: string; reasonCode: string }> {
    return this.request(`/packages/${packageId}/${step}/reject`, { method: 'POST', body: JSON.stringify({ reasonCode }) });
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
    const endWrite = beginWrite();
    try {
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
      return await response.json();
    } finally {
      endWrite();
    }
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
      // Task 0101: a page that never comes is shown as failed, with Try again, not as an endless spinner.
      signal: AbortSignal.timeout(PAGE_PREVIEW_TIMEOUT_MS),
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
      // Task 0023 D5: keep the HTTP status with the body, as request() does, so REVIEW_ALREADY_DECIDED (409) is recognised.
      if (parsed?.message || parsed?.errorCode) throw { ...parsed, httpStatus: response.status };
      throw { message: `HTTP ${response.status}: ${response.statusText}`, httpStatus: response.status };
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

  /** Task 0074: an unreadable page was checked by hand; each box is redacted. */
  async checkReviewPage(findingId: string, boxes: readonly ReviewBoundingBox[]): Promise<ReviewResolutionResult> {
    return this.request(`/review-items/${findingId}/page-checked`, { method: 'POST', body: JSON.stringify({ reviewed: true, boxes }) });
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
      // Task 0101: a page that never comes is shown as failed, with Try again, not as an endless spinner.
      signal: AbortSignal.timeout(PAGE_PREVIEW_TIMEOUT_MS),
    });
    if (!response.ok) {
      const raw = await response.text();
      let parsed: ApiError | null = null;
      if (raw) {
        try { parsed = JSON.parse(raw) as ApiError; } catch { /* non-JSON security/container response */ }
      }
      // Task 0023 D5: keep the HTTP status with the body, as request() does, so REVIEW_ALREADY_DECIDED (409) is recognised.
      if (parsed?.message || parsed?.errorCode) throw { ...parsed, httpStatus: response.status };
      throw { message: `HTTP ${response.status}: ${response.statusText}`, httpStatus: response.status };
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

  /**
   * U022: POST /compliance-reports. Returns the PDF and the server's Content-Disposition only; the caller downloads it
   * and keeps nothing (no file, response or hash) afterwards.
   */
  async createComplianceReport(body: object): Promise<{ pdf: Blob; disposition: string | null }> {
    const response = await this.request<Response>('/compliance-reports', {
      method: 'POST', headers: { Accept: 'application/pdf' }, body: JSON.stringify(body), cache: 'no-store',
    });
    return { pdf: await response.blob(), disposition: response.headers.get('content-disposition') };
  }

  /** Task 0019 A1: how many records the full audit trail appendix would print for the period. */
  /** Task 0041: the entity's sign-in access changes (platform-identity grant history), newest first, read-only. */
  async listGrantHistory(range: { from?: string; to?: string }): Promise<GrantHistoryRow[]> {
    const params = new URLSearchParams();
    if (range.from) params.append('from', range.from);
    if (range.to) params.append('to', range.to);
    const query = params.toString() ? `?${params.toString()}` : '';
    return this.request(`/audit-records/grant-history${query}`);
  }

  async auditRecordCount(query: Record<string, string>, signal?: AbortSignal): Promise<{ count: number }> {
    const params = new URLSearchParams(query);
    return this.request(`/audit-records/count?${params.toString()}`, { cache: 'no-store', signal });
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
