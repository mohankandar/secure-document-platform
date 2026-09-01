import { API_BASE_URL, defaultHeaders, baseHeaders } from './config';
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
  IdentifierDefinition,
  BusinessAdminConfiguration,
  BusinessAdminIdentifierDraft,
  DashboardAdminColumnConfiguration,
  SensitiveDataPolicyConfiguration,
  LifecyclePolicyConfiguration,
  PackageLifecycleSummary,
  OperationsDashboard,
  OperationalReports,
  DashboardStage,
  PlatformAdminOverview,
  CurrentUser,
} from '../types';

class ApiClient {
  private baseUrl = API_BASE_URL;

  private async request<T>(path: string, options: RequestInit = {}): Promise<T> {
    const url = `${this.baseUrl}${path}`;
    const headers = { ...defaultHeaders(), ...options.headers };

    const response = await fetch(url, { ...options, headers });

    if (!response.ok) {
      let error: ApiError = { message: 'Request failed' };
      try {
        error = await response.json();
      } catch {
        error.message = `HTTP ${response.status}: ${response.statusText}`;
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

  async getOperationalReports(): Promise<OperationalReports> {
    return this.request('/operations/reports');
  }

  // --- IT / Platform Admin (Unit 24) ---

  async getPlatformAdminOverview(): Promise<PlatformAdminOverview> {
    return this.request('/platform-admin/overview');
  }

  async runPlatformHealthCheck(): Promise<PlatformAdminOverview> {
    return this.request('/platform-admin/health-check', { method: 'POST' });
  }

  async scanPlatformWatchedFolder(): Promise<{ discovered: number; waitingForTrigger: number; accepted: number; rejected: number; duplicates: number }> {
    return this.request('/platform-admin/watched-folder/scan', { method: 'POST' });
  }

  async listPackages(): Promise<PackageSummary[]> {
    return this.request('/packages');
  }

  async getPackage(packageId: string): Promise<PackageDetail> {
    return this.request(`/packages/${packageId}`);
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
      headers: baseHeaders(),
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
      headers: { ...baseHeaders(), Accept: 'image/png, application/json' },
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
    const response = await fetch(url, { headers: baseHeaders() });
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
}

export const apiClient = new ApiClient();
