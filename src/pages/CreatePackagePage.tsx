import { useEffect, useMemo, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { apiClient } from '../api/client';
import type { ApiError, IdentifierDefinition } from '../types';
import './CreatePackagePage.css';
import PageScaffold from '../components/ui/PageScaffold';
import Skeleton from '../components/ui/Skeleton';
import EmptyState from '../components/ui/EmptyState';
import Icon from '../components/ui/Icon';

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

  const totalBytes = documents.reduce((sum, document) => sum + document.file.size, 0);
  const uploadedCount = documents.filter((document) => document.status === 'UPLOADED').length;
  const failedCount = documents.filter((document) => document.status === 'FAILED').length;
  const removableCount = documents.filter((document) => document.status === 'READY' || document.status === 'FAILED').length;
  const requiredCount = definitions.filter((definition) => definition.required).length;
  const completedRequired = definitions.filter((definition) => definition.required && (values[definition.key] || '').trim() && !identifierErrors[definition.key]).length;

  const clearRemovable = () => {
    setDocuments((current) => current.filter((document) => document.status === 'UPLOADING' || document.status === 'UPLOADED'));
    setSelectionMessage(null);
  };

  const statusLabel: Record<DraftStatus, string> = { READY: 'Ready', UPLOADING: 'Uploading…', UPLOADED: 'Uploaded', FAILED: 'Failed' };

  const checklist = [
    {
      label: 'Business identifiers',
      detail: loadingConfig ? 'Loading requirements…' : !configurationAvailable ? 'Not configured for tenant' : `${completedRequired} of ${requiredCount} required complete`,
      done: identifiersReady,
      problem: !loadingConfig && (!configurationAvailable || (hasIdentifierErrors && touchedIdentifiers.size > 0)),
    },
    {
      label: 'Documents',
      detail: documents.length === 0 ? 'No PDFs added' : `${documents.length} PDF${documents.length === 1 ? '' : 's'} · ${formatBytes(totalBytes)}`,
      done: documents.length > 0,
      problem: false,
    },
    {
      label: 'Uploads',
      detail: hasFailedDocuments ? `${failedCount} failed — retry or remove` : hasUploadInProgress ? `Uploading ${uploadedCount + 1} of ${documents.length}` : uploadedCount > 0 ? `${uploadedCount} of ${documents.length} uploaded` : 'Uploaded on submit',
      done: documents.length > 0 && !hasFailedDocuments && !hasUploadInProgress,
      problem: hasFailedDocuments,
    },
  ];

  const dropHandlers = {
    onDragEnter: (event: React.DragEvent<HTMLDivElement>) => { event.preventDefault(); if (!documentSelectionLocked) setDragActive(true); },
    onDragOver: (event: React.DragEvent<HTMLDivElement>) => event.preventDefault(),
    onDragLeave: (event: React.DragEvent<HTMLDivElement>) => {
      if (!event.currentTarget.contains(event.relatedTarget as Node | null)) setDragActive(false);
    },
    onDrop: handleDrop,
  };

  return (
    <PageScaffold className="intake-page" breadcrumbs={[{ label: 'Upload Documents' }]} title="Upload Documents"
      eyebrow="Document intake" description="Identify the intake, attach its PDFs, and start processing once — nothing runs until you submit.">

      {error && <div className="ui-alert tone-danger" role="alert">{error}</div>}

      <div className="intake-station">
        <div className="intake-rail">
          <section className="ui-card intake-identity" aria-labelledby="business-identifiers-heading">
            <header className="intake-panel-head">
              <div>
                <h2 id="business-identifiers-heading">Package identity</h2>
                <p>{fieldsLocked && draftPackageId ? 'Locked — the intake has been created.' : 'Required fields are defined by your organization.'}</p>
              </div>
              {!loadingConfig && configurationAvailable && (
                <span className={`intake-readiness-chip${identifiersReady ? ' is-ready' : ''}`}>
                  {identifiersReady ? <><Icon name="check" />Ready</> : `${completedRequired} of ${requiredCount} required`}
                </span>
              )}
            </header>

            {loadingConfig ? (
              <div className="intake-identifier-list" aria-busy="true">
                {[0, 1, 2].map((index) => <Skeleton key={index} className="intake-field-skeleton" />)}
              </div>
            ) : definitions.length === 0 ? (
              <EmptyState className="intake-config-empty">
                <Icon name="admin" />
                <strong>No business identifier fields are configured</strong>
                <span>A tenant administrator must define intake identifiers before documents can be submitted.</span>
              </EmptyState>
            ) : (
              <div className="intake-identifier-list">
                {definitions.map((definition) => {
                  const visibleError = touchedIdentifiers.has(definition.key) ? identifierErrors[definition.key] : null;
                  const valid = !identifierErrors[definition.key] && !!(values[definition.key] || '').trim();
                  const hint = definition.exactLength != null ? `${definition.exactLength} characters`
                    : definition.validationType === 'NUMERIC' ? 'Digits only'
                      : definition.allowedValues.length > 0 ? `One of: ${definition.allowedValues.join(', ')}` : null;
                  return (
                  <div className={`intake-field${definition.primary ? ' is-primary' : ''}${visibleError ? ' has-error' : ''}${valid ? ' is-valid' : ''}`} key={definition.key}>
                    <label htmlFor={`identifier-${definition.key}`}>
                      <span>{definition.label}{definition.primary && <span className="intake-primary-label">Primary</span>}</span>
                      {definition.required
                        ? <span className="intake-required">Required<span className="sr-only"> field</span></span>
                        : <span className="intake-optional">Optional</span>}
                    </label>
                    <div className="intake-field-control">
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
                        spellCheck={false}
                        inputMode={definition.validationType === 'NUMERIC' ? 'numeric' : undefined}
                        maxLength={definition.stripSeparators ? 512 : (definition.exactLength ?? definition.maxLength ?? 512)}
                        aria-invalid={!!visibleError}
                        aria-describedby={visibleError ? `identifier-${definition.key}-error` : hint ? `identifier-${definition.key}-hint` : undefined}
                      />
                      {valid && <Icon name="check" className="intake-field-check" />}
                    </div>
                    {visibleError
                      ? <small id={`identifier-${definition.key}-error`} className="field-error">{visibleError}</small>
                      : hint && <small id={`identifier-${definition.key}-hint`} className="intake-field-hint">{hint}</small>}
                  </div>
                  );
                })}
              </div>
            )}
          </section>

          <aside className="ui-card intake-submit-panel" aria-labelledby="submit-documents-heading">
            <h2 id="submit-documents-heading">Submit Documents</h2>
            <ol className="intake-checklist">
              {checklist.map((item) => (
                <li key={item.label} className={item.done ? 'is-done' : item.problem ? 'is-problem' : ''}>
                  <span className="intake-check-mark" aria-hidden="true">{item.done && <Icon name="check" />}</span>
                  <div>
                    <strong>{item.label}<span className="sr-only">{item.done ? ' — complete' : ' — incomplete'}</span></strong>
                    <small>{item.detail}</small>
                  </div>
                </li>
              ))}
            </ol>
            {submitting && documents.length > 0 && (
              <div className="intake-upload-progress" role="progressbar" aria-label="Upload progress" aria-valuemin={0} aria-valuemax={documents.length} aria-valuenow={uploadedCount}>
                <i style={{ width: `${(uploadedCount / documents.length) * 100}%` }} />
              </div>
            )}
            <p className={`intake-readiness${canSubmit ? ' is-ready' : ''}`}>{readinessMessage}</p>
            <button className="ui-button primary intake-submit-button" type="button" onClick={() => void handleSubmit()} disabled={!canSubmit}>
              {submitting ? 'Submitting…' : hasFailedDocuments ? 'Retry Uploads & Submit' : 'Submit Documents'}
            </button>
            {draftPackageId && error && (
              <Link className="intake-draft-link" to={`/packages/${draftPackageId}`}>Open incomplete intake details</Link>
            )}
            <p className="intake-footnote"><Icon name="shield" />Processing begins only after submission. Identifiers lock once the intake is created.</p>
          </aside>
        </div>

        <section className={`ui-card intake-documents${dragActive ? ' is-dragging' : ''}`} aria-labelledby="documents-heading" {...dropHandlers}>
          <header className="intake-panel-head intake-documents-head">
            <div>
              <h2 id="documents-heading">Documents</h2>
              <p>{documentsReady && uploadedCount === documents.length ? 'All PDFs uploaded.' : 'Every PDF stays a separate document with its original filename.'}</p>
            </div>
            {documents.length > 0 && (
              <div className="intake-documents-tools">
                <span className="intake-ledger-summary">{documents.length} PDF{documents.length === 1 ? '' : 's'} · {formatBytes(totalBytes)}</span>
                {removableCount > 1 && !submitting && (
                  <button type="button" className="ui-button tertiary" onClick={clearRemovable}>Remove all pending</button>
                )}
                <button type="button" className="ui-button secondary" onClick={() => browseInputRef.current?.click()} disabled={documentSelectionLocked}>
                  <Icon name="upload" />Add PDFs
                </button>
              </div>
            )}
          </header>

          <input
            ref={browseInputRef}
            className="intake-hidden-input"
            type="file"
            accept="application/pdf,.pdf"
            multiple
            aria-label="Choose PDF documents"
            onChange={(event) => handleFileSelection(event.target.files)}
            tabIndex={-1}
          />

          {documents.length === 0 ? (
            <div className={`intake-drop-zone${dragActive ? ' is-dragging' : ''}${documentSelectionLocked ? ' is-disabled' : ''}`}>
              <div className="intake-drop-stack" aria-hidden="true">
                <span /><span /><span>PDF</span>
              </div>
              <div className="intake-drop-copy">
                <strong>{dragActive ? 'Release to add PDFs' : 'Drop the package PDFs here'}</strong>
                <p>PDF only · duplicates are skipped automatically</p>
              </div>
              <button type="button" className="ui-button primary" onClick={() => browseInputRef.current?.click()} disabled={documentSelectionLocked}>
                Browse Files
              </button>
            </div>
          ) : (
            <div className="intake-ledger-wrap">
              <ol className="intake-document-queue" aria-label="Document queue">
                {documents.map((document, index) => (
                  <li key={document.id} className={`is-${document.status.toLowerCase()}`}>
                    <span className="intake-document-index" aria-hidden="true">{String(index + 1).padStart(2, '0')}</span>
                    <span className="intake-document-icon" aria-hidden="true">PDF</span>
                    <div className="intake-document-summary">
                      <strong title={document.file.name}>{document.file.name}</strong>
                      {document.error ? <span className="intake-document-error">{document.error}</span> : <span>{formatBytes(document.file.size)}</span>}
                    </div>
                    <span className={`intake-upload-status status-${document.status.toLowerCase()}`}>
                      {document.status === 'UPLOADED' && <Icon name="check" />}
                      {statusLabel[document.status]}
                    </span>
                    <button
                      type="button"
                      className="intake-remove-document"
                      onClick={() => removeDocument(document.id)}
                      disabled={document.status === 'UPLOADING' || document.status === 'UPLOADED' || submitting}
                      aria-label={`Remove ${document.file.name}`}
                      title="Remove"
                    >
                      <Icon name="close" />
                    </button>
                  </li>
                ))}
              </ol>
              {dragActive && <div className="intake-drop-overlay" aria-hidden="true"><Icon name="upload" />Release to add PDFs</div>}
            </div>
          )}

          <div className="intake-selection-message" role="status" aria-live="polite">{selectionMessage}</div>
        </section>
      </div>
    </PageScaffold>
  );
}
