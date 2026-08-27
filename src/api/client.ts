import { API_BASE_URL, defaultHeaders, baseHeaders } from './config';
import type {
  PackageSummary,
  PackageDetail,
  CreatePackageRequest,
  PackageStatusResult,
  DocumentUploadResult,
  ReviewTaskResult,
  AccessRequestResult,
  VaultEntryResult,
  ApiError,
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

  // --- Package intake ---

  async listPackages(): Promise<PackageSummary[]> {
    return this.request('/packages');
  }

  async getPackage(packageId: string): Promise<PackageDetail> {
    return this.request(`/packages/${packageId}`);
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

  // --- Review ---

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

  // --- Vault / Access ---

  async registerVaultEntry(packageId: string, documentId: string, artifactId: string): Promise<VaultEntryResult> {
    return this.request('/vault/entries', {
      method: 'POST',
      body: JSON.stringify({ packageId, documentId, artifactId }),
    });
  }

  async requestArtifactAccess(vaultEntryId: string, artifactId: string, justification: string, durationHours?: number): Promise<AccessRequestResult> {
    return this.request('/artifact-access-requests', {
      method: 'POST',
      body: JSON.stringify({ vaultEntryId, artifactId, justification, durationHours: durationHours || 24 }),
    });
  }

  async listAccessRequests(): Promise<AccessRequestResult[]> {
    return this.request('/artifact-access-requests');
  }

  async getAccessRequest(requestId: string): Promise<AccessRequestResult> {
    return this.request(`/artifact-access-requests/${requestId}`);
  }

  async approveAccessRequest(requestId: string, reason?: string): Promise<AccessRequestResult> {
    return this.request(`/artifact-access-requests/${requestId}/approve`, {
      method: 'POST',
      body: JSON.stringify({ reason: reason || '' }),
    });
  }

  async denyAccessRequest(requestId: string, reason: string): Promise<AccessRequestResult> {
    return this.request(`/artifact-access-requests/${requestId}/deny`, {
      method: 'POST',
      body: JSON.stringify({ reason }),
    });
  }

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

  async downloadOriginalArtifact(documentId: string): Promise<Blob> {
    const url = `${this.baseUrl}/documents/${documentId}/artifacts/original/content`;
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
}

export const apiClient = new ApiClient();
