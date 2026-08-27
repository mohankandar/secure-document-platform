export interface PackageSummary {
  packageId: string;
  tenantId?: string;
  packageType: string;
  status: string;
  documentCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface PackageDetail extends PackageSummary {
  documents: DocumentSummary[];
}

export interface DocumentSummary {
  documentId: string;
  fileName?: string; // present in backend but not displayed in UI
  mimeType: string;
  sizeBytes: number;
  documentType?: string;
  status: string;
}

export interface CreatePackageRequest {
  packageType: string;
  title?: string;
  description?: string;
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
