import { useEffect, useMemo, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { apiClient } from '../api/client';
import type { ApiError, IdentifierDefinition } from '../types';
import Breadcrumbs from '../components/Breadcrumbs';
import './CreatePackagePage.css';
import PageHeader from '../components/ui/PageHeader';
import WorkflowStepper from '../components/ui/WorkflowStepper';

type DraftStatus = 'READY' | 'UPLOADING' | 'UPLOADED' | 'FAILED';

interface DraftDocument {
  id: string;
  file: File;
  status: DraftStatus;
  error?: string;
}

const formatBytes = (bytes: number) => {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
};

const isPdf = (file: File) => {
  const hasPdfName = file.name.toLowerCase().endsWith('.pdf');
  return hasPdfName && (!file.type || file.type === 'application/pdf');
};

const fileIdentity = (file: File) => `${file.name.toLowerCase()}::${file.size}::${file.lastModified}`;

const canonicalIdentifierValue = (definition: IdentifierDefinition, raw: string) => {
  let value = raw.trim().replace(/\s+/g, ' ');
  if (definition.stripSeparators) value = value.replace(/[ -]/g, '');
  if (definition.caseNormalization === 'UPPER') value = value.toUpperCase();
  if (definition.caseNormalization === 'LOWER') value = value.toLowerCase();
  return value;
};

const validateIdentifierValue = (definition: IdentifierDefinition, raw: string): string | null => {
  if (!raw.trim()) return definition.required ? `${definition.label} is required.` : null;
  const value = canonicalIdentifierValue(definition, raw);
  if (definition.validationType === 'NUMERIC' && !/^[0-9]+$/.test(value)) return `${definition.label} must contain digits only.`;
  if (definition.validationType === 'ALPHANUMERIC' && !/^[A-Za-z0-9]+$/.test(value)) return `${definition.label} must contain letters and digits only.`;
  if (definition.validationType === 'EMAIL' && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value)) return `${definition.label} must be a valid email address.`;
  if (definition.validationType === 'UUID' && !/^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/.test(value)) return `${definition.label} must be a valid UUID.`;
  // CUSTOM_PATTERN uses the backend's Java regex engine. Do not duplicate that rule in
  // JavaScript because the two regex dialects are not guaranteed to be equivalent. The backend
  // remains authoritative for custom patterns while the UI still validates portable constraints.
  if (definition.exactLength != null && value.length !== definition.exactLength) return `${definition.label} must contain exactly ${definition.exactLength} characters.`;
  if (definition.minLength != null && value.length < definition.minLength) return `${definition.label} must contain at least ${definition.minLength} characters.`;
  if (definition.maxLength != null && value.length > definition.maxLength) return `${definition.label} must contain no more than ${definition.maxLength} characters.`;
  if (definition.allowedValues.length > 0) {
    const allowed = definition.allowedValues.map((item) => canonicalIdentifierValue(definition, item));
    if (!allowed.includes(value)) return `${definition.label} must be one of: ${definition.allowedValues.join(', ')}.`;
  }
  return null;
};

export default function CreatePackagePage() {
  const navigate = useNavigate();
  const browseInputRef = useRef<HTMLInputElement>(null);
  const nextDraftId = useRef(0);
  const [definitions, setDefinitions] = useState<IdentifierDefinition[]>([]);
  const [values, setValues] = useState<Record<string, string>>({});
  const [touchedIdentifiers, setTouchedIdentifiers] = useState<Set<string>>(() => new Set());
  const [documents, setDocuments] = useState<DraftDocument[]>([]);
  const [draftPackageId, setDraftPackageId] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [dragActive, setDragActive] = useState(false);
  const [loadingConfig, setLoadingConfig] = useState(true);
  const [configurationAvailable, setConfigurationAvailable] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [selectionMessage, setSelectionMessage] = useState<string | null>(null);

  useEffect(() => {
    apiClient.listIdentifierDefinitions()
      .then((defs) => {
        const sorted = [...defs].sort((a, b) => a.displayOrder - b.displayOrder);
        setDefinitions(sorted);
        setConfigurationAvailable(sorted.length > 0);
      })
      .catch((err: ApiError) => {
        setConfigurationAvailable(false);
        setError(err.message || 'Unable to load intake configuration');
      })
      .finally(() => setLoadingConfig(false));
  }, []);

  const identifierErrors = useMemo(() => Object.fromEntries(
    definitions.map((definition) => [definition.key, validateIdentifierValue(definition, values[definition.key] || '')])
  ), [definitions, values]);
  const missingRequired = useMemo(
    () => definitions.some((definition) => definition.required && !(values[definition.key] || '').trim()),
    [definitions, values]
  );
  const hasIdentifierErrors = Object.values(identifierErrors).some(Boolean);

  const hasUploadInProgress = documents.some((document) => document.status === 'UPLOADING');
  const hasFailedDocuments = documents.some((document) => document.status === 'FAILED');
  const fieldsLocked = submitting || draftPackageId !== null;
  const documentSelectionLocked = submitting;
  const identifiersReady = configurationAvailable && !loadingConfig && !missingRequired && !hasIdentifierErrors;
  const documentsReady = documents.length > 0 && !hasUploadInProgress && !hasFailedDocuments;
  const canSubmit = configurationAvailable
    && !loadingConfig
    && !missingRequired
    && !hasIdentifierErrors
    && documents.length > 0
    && !submitting
    && !hasUploadInProgress;

  const readinessMessage = loadingConfig
    ? 'Loading tenant intake requirements…'
    : !configurationAvailable
      ? 'Tenant intake fields must be configured before documents can be submitted.'
      : missingRequired
        ? 'Complete all required business identifiers.'
        : hasIdentifierErrors
          ? 'Correct the highlighted business identifier values.'
        : documents.length === 0
          ? 'Add at least one PDF document.'
          : hasFailedDocuments
            ? 'Retry failed uploads or remove them before submitting.'
            : 'Ready to submit. Processing will begin only after you select Submit Documents.';

  const updateDocument = (id: string, update: Partial<DraftDocument>) => {
    setDocuments((current) => current.map((document) => (
      document.id === id ? { ...document, ...update } : document
    )));
  };

  const addDocuments = (files: File[]) => {
    setSelectionMessage(null);
    const invalid = files.filter((file) => !isPdf(file));
    const valid = files.filter(isPdf);
    const existing = new Set(documents.map((document) => fileIdentity(document.file)));
    const additions = valid
      .filter((file) => !existing.has(fileIdentity(file)))
      .map((file) => {
        existing.add(fileIdentity(file));
        nextDraftId.current += 1;
        return {
          id: `draft-${nextDraftId.current}`,
          file,
          status: 'READY' as const,
        };
      });

    const duplicates = valid.length - additions.length;
    const messages: string[] = [];
    if (additions.length > 0) messages.push(`${additions.length} PDF${additions.length === 1 ? '' : 's'} added`);
    if (duplicates > 0) messages.push(`${duplicates} duplicate${duplicates === 1 ? '' : 's'} skipped`);
    if (invalid.length > 0) messages.push(`${invalid.length} non-PDF file${invalid.length === 1 ? '' : 's'} rejected`);
    setSelectionMessage(messages.length > 0 ? `${messages.join('; ')}.` : 'No new PDFs were added.');
    setDocuments((current) => [...current, ...additions]);
  };

  const handleFileSelection = (files: FileList | null) => {
    if (!files || files.length === 0) return;
    addDocuments(Array.from(files));
    if (browseInputRef.current) browseInputRef.current.value = '';
  };

  const removeDocument = (id: string) => {
    setDocuments((current) => current.filter((document) => document.id !== id));
    setSelectionMessage(null);
  };

  const handleDrop = (event: React.DragEvent<HTMLDivElement>) => {
    event.preventDefault();
    setDragActive(false);
    if (documentSelectionLocked) return;
    handleFileSelection(event.dataTransfer.files);
  };

  const handleSubmit = async () => {
    if (!canSubmit) return;
    setSubmitting(true);
    setError(null);
    setSelectionMessage(null);

    let packageId = draftPackageId;
    try {
      if (!packageId) {
        const businessIdentifiers = Object.fromEntries(
          definitions
            .map((definition) => [definition.key, (values[definition.key] || '').trim()] as const)
            .filter(([, value]) => value.length > 0)
        );
        const created = await apiClient.createPackage({
          packageType: 'MANUAL_UPLOAD',
          businessIdentifiers,
        });
        packageId = created.packageId;
        setDraftPackageId(packageId);
      }

      let failedUploads = 0;
      for (const document of documents.filter((item) => item.status !== 'UPLOADED')) {
        updateDocument(document.id, { status: 'UPLOADING', error: undefined });
        try {
          await apiClient.uploadDocument(packageId, document.file);
          updateDocument(document.id, { status: 'UPLOADED', error: undefined });
        } catch (uploadError) {
          const apiError = uploadError as ApiError;
          failedUploads += 1;
          updateDocument(document.id, {
            status: 'FAILED',
            error: apiError.message || 'Upload failed',
          });
        }
      }

      if (failedUploads > 0) {
        setError('The intake was not submitted because one or more PDFs failed to upload. Retry or remove the failed files; processing has not started.');
        return;
      }

      await apiClient.submitPackage(packageId);
      navigate(`/packages/${packageId}`);
    } catch (submitError) {
      const apiError = submitError as ApiError;
      setError(apiError.message || 'Unable to submit documents');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="intake-page">
      <Breadcrumbs items={[{ label: 'Upload Documents' }]} />
      <PageHeader eyebrow="Manual intake" title="Upload Documents" description="Create one intake, add every PDF that belongs together, then submit once for processing." />

      <WorkflowStepper
        ariaLabel="Manual intake progress"
        steps={[
          { label: 'Business details', description: 'Identify the intake', state: identifiersReady ? 'complete' : 'current' },
          { label: 'Documents', description: 'Add one or more PDFs', state: !identifiersReady ? 'upcoming' : documentsReady ? 'complete' : 'current' },
          { label: 'Review & submit', description: 'Start processing once', state: identifiersReady && documentsReady ? 'current' : 'upcoming' },
        ]}
      />

      {error && <div className="error-msg" role="alert">{error}</div>}
      {selectionMessage && <div className="intake-selection-message" role="status">{selectionMessage}</div>}

      <div className="intake-workspace">
        <section className="card intake-section" aria-labelledby="business-identifiers-heading">
          <div className="intake-section-heading">
            <span>1</span>
            <div>
              <h2 id="business-identifiers-heading">Business identifiers</h2>
              <p>Required fields are defined by your organization.</p>
            </div>
          </div>

          {loadingConfig ? (
            <p>Loading intake fields…</p>
          ) : definitions.length === 0 ? (
            <p>No business identifier fields are configured for this tenant.</p>
          ) : (
            <div className="intake-identifier-grid">
              {definitions.map((definition) => {
                const visibleError = touchedIdentifiers.has(definition.key) ? identifierErrors[definition.key] : null;
                return (
                <div className="form-group" key={definition.key}>
                  <label htmlFor={`identifier-${definition.key}`}>
                    {definition.label}
                    {definition.required && <span aria-label="required"> *</span>}
                    {definition.primary && <span className="intake-primary-label">Primary</span>}
                  </label>
                  <input
                    id={`identifier-${definition.key}`}
                    value={values[definition.key] || ''}
                    onChange={(event) => setValues((current) => ({
                      ...current,
                      [definition.key]: event.target.value,
                    }))}
                    onBlur={() => setTouchedIdentifiers((current) => {
                      const next = new Set(current);
                      next.add(definition.key);
                      return next;
                    })}
                    required={definition.required}
                    disabled={fieldsLocked}
                    autoComplete="off"
                    inputMode={definition.validationType === 'NUMERIC' ? 'numeric' : undefined}
                    maxLength={definition.stripSeparators ? 512 : (definition.exactLength ?? definition.maxLength ?? 512)}
                    aria-invalid={!!visibleError}
                    aria-describedby={visibleError ? `identifier-${definition.key}-error` : undefined}
                  />
                  {visibleError && (
                    <small id={`identifier-${definition.key}-error`} className="field-error">{visibleError}</small>
                  )}
                </div>
                );
              })}
            </div>
          )}
        </section>

        <section className="card intake-section" aria-labelledby="documents-heading">
          <div className="intake-section-heading">
            <span>2</span>
            <div>
              <h2 id="documents-heading">Documents</h2>
              <p>Every PDF remains a separate document with its original filename.</p>
            </div>
          </div>

          <div
            className={`intake-drop-zone${dragActive ? ' is-dragging' : ''}${documentSelectionLocked ? ' is-disabled' : ''}`}
            onDragEnter={(event) => { event.preventDefault(); if (!documentSelectionLocked) setDragActive(true); }}
            onDragOver={(event) => event.preventDefault()}
            onDragLeave={(event) => {
              if (!event.currentTarget.contains(event.relatedTarget as Node | null)) setDragActive(false);
            }}
            onDrop={handleDrop}
          >
            <div className="intake-drop-icon" aria-hidden="true">PDF</div>
            <strong>Drag and drop PDFs here</strong>
            <p>Or browse for PDFs. Multiple PDFs can be added to the same intake.</p>
            <div className="intake-source-actions">
              <button
                type="button"
                className="primary"
                onClick={() => browseInputRef.current?.click()}
                disabled={documentSelectionLocked}
              >
                Browse Files
              </button>
            </div>
            <input
              ref={browseInputRef}
              className="intake-hidden-input"
              type="file"
              accept="application/pdf,.pdf"
              multiple
              onChange={(event) => handleFileSelection(event.target.files)}
              tabIndex={-1}
            />
          </div>

          <div className="intake-queue-heading">
            <h3>Document queue</h3>
            <span>{documents.length} PDF{documents.length === 1 ? '' : 's'}</span>
          </div>

          {documents.length === 0 ? (
            <div className="intake-empty-queue">No documents added yet.</div>
          ) : (
            <ul className="intake-document-queue">
              {documents.map((document) => (
                <li key={document.id}>
                  <div className="intake-document-icon" aria-hidden="true">PDF</div>
                  <div className="intake-document-summary">
                    <strong title={document.file.name}>{document.file.name}</strong>
                    <span>{formatBytes(document.file.size)} · Browse / Drop</span>
                    {document.error && <span className="intake-document-error">{document.error}</span>}
                  </div>
                  <span className={`intake-upload-status status-${document.status.toLowerCase()}`}>
                    {document.status === 'READY' ? 'Ready' : document.status === 'UPLOADING' ? 'Uploading…' : document.status === 'UPLOADED' ? 'Uploaded' : 'Failed'}
                  </span>
                  <button
                    type="button"
                    className="intake-remove-document"
                    onClick={() => removeDocument(document.id)}
                    disabled={document.status === 'UPLOADING' || document.status === 'UPLOADED' || submitting}
                    aria-label={`Remove ${document.file.name}`}
                  >
                    Remove
                  </button>
                </li>
              ))}
            </ul>
          )}
        </section>

        <section className="card intake-submit-panel" aria-labelledby="submit-documents-heading">
          <div>
            <h2 id="submit-documents-heading">Submit Documents</h2>
            <p>{readinessMessage}</p>
            {draftPackageId && error && (
              <Link to={`/packages/${draftPackageId}`}>Open incomplete intake details</Link>
            )}
          </div>
          <button className="primary intake-submit-button" onClick={() => void handleSubmit()} disabled={!canSubmit}>
            {submitting ? 'Submitting…' : hasFailedDocuments ? 'Retry Uploads & Submit' : 'Submit Documents'}
          </button>
        </section>
      </div>
    </div>
  );
}
