import { useEffect, useMemo, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { apiClient } from '../api/client';
import { scanBridgeClient, type ScanBridgeConfiguration, type ScanBridgeError, type ScanSession, type ScannerStatus } from '../api/scanBridgeClient';
import type { ApiError, IdentifierDefinition } from '../types';
import './ScanIntakePage.css';
import { useBranding } from '../branding/BrandingContext';
import PageScaffold from '../components/ui/PageScaffold';
import Icon from '../components/ui/Icon';

const formatBytes = (bytes: number) => {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  return `${(bytes / (1024 * 1024 * 1024)).toFixed(1)} GB`;
};

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
  if (definition.exactLength != null && value.length !== definition.exactLength) return `${definition.label} must contain exactly ${definition.exactLength} characters.`;
  if (definition.minLength != null && value.length < definition.minLength) return `${definition.label} must contain at least ${definition.minLength} characters.`;
  if (definition.maxLength != null && value.length > definition.maxLength) return `${definition.label} must contain no more than ${definition.maxLength} characters.`;
  if (definition.allowedValues.length > 0) {
    const allowed = definition.allowedValues.map((item) => canonicalIdentifierValue(definition, item));
    if (!allowed.includes(value)) return `${definition.label} must be one of: ${definition.allowedValues.join(', ')}.`;
  }
  return null;
};

const isBridgeUnavailableError = (error: ScanBridgeError) => error.source === 'scanBridge' && !error.errorCode;
const LOST_SESSION_MESSAGE = 'The previous scan session is no longer active. Start a new scan.';

type LifecycleState = 'complete' | 'current' | 'upcoming' | 'attention';
const LIFECYCLE = [
  { key: 'identify', label: 'Identify', detail: 'Business fields' },
  { key: 'capture', label: 'Capture', detail: 'One consolidated PDF' },
  { key: 'verify', label: 'Verify', detail: 'Stable file check' },
  { key: 'queue', label: 'Hand off', detail: 'Durable delivery' },
] as const;

const lifecycleIndexFor = (session: ScanSession | null) => {
  if (!session) return 0;
  switch (session.status) {
    case 'WAITING_FOR_SCAN': return 1;
    case 'READY': return 2;
    case 'QUEUED':
    case 'SUBMITTING':
    case 'ATTENTION_REQUIRED': return 3;
    case 'SUBMITTED': return 4;
    default: return 1;
  }
};

export default function ScanIntakePage() {
  const { branding } = useBranding();
  const [definitions, setDefinitions] = useState<IdentifierDefinition[]>([]);
  const [bridgeConfig, setBridgeConfig] = useState<ScanBridgeConfiguration | null>(null);
  const [scannerStatus, setScannerStatus] = useState<ScannerStatus | null>(null);
  const [bridgeAvailable, setBridgeAvailable] = useState<boolean | null>(null);
  const [identifierValues, setIdentifierValues] = useState<Record<string, string>>({});
  const [touched, setTouched] = useState<Record<string, boolean>>({});
  const [session, setSession] = useState<ScanSession | null>(null);
  const [loading, setLoading] = useState(true);
  const [starting, setStarting] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [staging, setStaging] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [bridgeError, setBridgeError] = useState<string | null>(null);
  const testPdfInput = useRef<HTMLInputElement>(null);

  useEffect(() => {
    let active = true;
    const load = async () => {
      try {
        const identifierDefinitions = await apiClient.listIdentifierDefinitions();
        if (active) setDefinitions([...identifierDefinitions].sort((a, b) => a.displayOrder - b.displayOrder));
      } catch (err) {
        const apiError = err as ApiError;
        if (active) setError(apiError.message || 'Unable to load tenant intake requirements.');
      }
      try {
        const [configuration, status] = await Promise.all([
          scanBridgeClient.getConfiguration(), scanBridgeClient.getScannerStatus(),
        ]);
        if (active) {
          setBridgeConfig(configuration);
          setScannerStatus(status);
          setBridgeAvailable(true);
          setBridgeError(null);
        }
        try {
          const currentSession = await scanBridgeClient.getCurrentSession();
          if (active && currentSession) {
            setSession(currentSession);
            setIdentifierValues(currentSession.businessIdentifiers);
            setNotice('Recovered the scan package that was waiting for operator action.');
          }
        } catch (recoveryError) {
          const scanError = recoveryError as ScanBridgeError;
          if (active && isBridgeUnavailableError(scanError)) {
            setBridgeAvailable(false);
            setBridgeError(scanError.message || 'Local Scanner Bridge is unavailable.');
          } else if (active) {
            setError(scanError.message || 'Unable to recover the current scanner package.');
          }
        }
      } catch (err) {
        const scanError = err as ScanBridgeError;
        if (active) {
          setBridgeAvailable(false);
          setBridgeError(scanError.message || 'Local Scanner Bridge is unavailable.');
        }
      } finally {
        if (active) setLoading(false);
      }
    };
    void load();
    return () => { active = false; };
  }, []);

  useEffect(() => {
    let cancelled = false;
    const refreshAvailability = async () => {
      try {
        const status = await scanBridgeClient.getScannerStatus();
        if (cancelled) return;
        setScannerStatus(status);
        setBridgeAvailable(true);
        setBridgeError(null);

        // A bridge restart rotates the browser request token. Refresh configuration as soon as the
        // process instance changes so the next Start/Submit/Cancel mutation is trusted without a
        // browser reload. The client also retries once defensively if a mutation races the restart.
        if (!bridgeConfig || bridgeConfig.bridgeInstanceId !== status.bridgeInstanceId) {
          try {
            const configuration = await scanBridgeClient.getConfiguration();
            if (!cancelled) {
              const restarted = !!bridgeConfig && bridgeConfig.bridgeInstanceId !== configuration.bridgeInstanceId;
              setBridgeConfig(configuration);
              if (restarted) setNotice('Scanner Bridge reconnected.');
            }
          } catch { /* retry on the next health poll */ }
        }
        if (!session) {
          try {
            const currentSession = await scanBridgeClient.getCurrentSession();
            if (!cancelled && currentSession) {
              setSession(currentSession);
              setIdentifierValues(currentSession.businessIdentifiers);
              setNotice('Recovered the scan package that was waiting for operator action.');
            }
          } catch { /* scanner status already proves bridge reachability; retry recovery on next poll */ }
        }
      } catch (err) {
        if (cancelled) return;
        const scanError = err as ScanBridgeError;
        setBridgeAvailable(false);
        setScannerStatus(null);
        setBridgeError(scanError.message || 'Local Scanner Bridge is unavailable. Start the bridge service and try again.');
      }
    };
    const timer = window.setInterval(() => void refreshAvailability(), 2000);
    return () => { cancelled = true; window.clearInterval(timer); };
  }, [bridgeConfig, session?.sessionId]);

  useEffect(() => {
    if (!session || !['WAITING_FOR_SCAN', 'QUEUED', 'SUBMITTING'].includes(session.status)) return;
    let cancelled = false;
    const refreshSession = () => {
      scanBridgeClient.getSession(session.sessionId)
        .then((latest) => {
          if (cancelled) return;
          setBridgeAvailable(true);
          setBridgeError(null);
          setSession(latest);
        })
        .catch((err: ScanBridgeError) => {
          if (cancelled) return;
          if (err.errorCode === 'SCAN_SESSION_NOT_FOUND') {
            setSession(null);
            setError(LOST_SESSION_MESSAGE);
            return;
          }
          if (isBridgeUnavailableError(err)) {
            setBridgeAvailable(false);
            setBridgeError(err.message || 'Local Scanner Bridge is unavailable.');
            return;
          }
          setError(err.message || 'Unable to read scanner status.');
        });
    };
    void refreshSession();
    const timer = window.setInterval(refreshSession, 1000);
    return () => { cancelled = true; window.clearInterval(timer); };
  }, [session?.sessionId, session?.status]);

  const identifierErrors = useMemo(() => Object.fromEntries(
    definitions.map((definition) => [definition.key, validateIdentifierValue(definition, identifierValues[definition.key] || '')])
  ), [definitions, identifierValues]);

  const identifiersReady = definitions.length > 0 && !Object.values(identifierErrors).some(Boolean);
  const storageHealthy = scannerStatus?.storageHealthy !== false;
  const acquisitionAvailable = scannerStatus?.acquisitionAvailable === true;
  const canStart = !loading && bridgeAvailable === true && !!bridgeConfig?.enabled && acquisitionAvailable && storageHealthy
    && scannerStatus?.busy !== true && definitions.length > 0 && !Object.values(identifierErrors).some(Boolean) && !session && !starting;

  const requiredDefinitions = definitions.filter((definition) => definition.required);
  const requiredComplete = requiredDefinitions.filter((definition) => !identifierErrors[definition.key]).length;

  const startSession = async () => {
    if (!canStart) return;
    setStarting(true); setError(null); setNotice(null);
    try {
      const businessIdentifiers = Object.fromEntries(definitions
        .map((definition) => [definition.key, canonicalIdentifierValue(definition, identifierValues[definition.key] || '')] as const)
        .filter(([, value]) => value.length > 0));
      setSession(await scanBridgeClient.startSession(businessIdentifiers));
      setBridgeAvailable(true); setBridgeError(null);
    } catch (err) {
      const scanError = err as ScanBridgeError;
      if (isBridgeUnavailableError(scanError)) { setBridgeAvailable(false); setBridgeError(scanError.message); }
      else setError(scanError.message || 'Unable to start scan.');
    } finally { setStarting(false); }
  };

  const cancelSession = async () => {
    if (!session || !['WAITING_FOR_SCAN', 'READY'].includes(session.status)) return;
    setError(null); setNotice(null);
    try {
      await scanBridgeClient.cancelSession(session.sessionId);
      setSession(null);
    } catch (err) {
      const scanError = err as ScanBridgeError;
      if (scanError.errorCode === 'SCAN_SESSION_NOT_FOUND') { setSession(null); setError(LOST_SESSION_MESSAGE); return; }
      if (isBridgeUnavailableError(scanError)) { setBridgeAvailable(false); setBridgeError(scanError.message); }
      else setError(scanError.message || 'Unable to cancel scan.');
    }
  };

  const stageTestPdf = async (file: File | undefined) => {
    if (!file || !session || session.status !== 'WAITING_FOR_SCAN') return;
    setStaging(true); setError(null); setNotice(null);
    try {
      await scanBridgeClient.stageSimulatorPdf(session.sessionId, file);
      setNotice('Test PDF received by the Scanner Simulator. Waiting for the stable-file check to complete.');
    } catch (err) {
      const scanError = err as ScanBridgeError;
      if (isBridgeUnavailableError(scanError)) { setBridgeAvailable(false); setBridgeError(scanError.message); }
      else setError(scanError.message || 'Unable to stage the test PDF.');
    } finally {
      setStaging(false);
      if (testPdfInput.current) testPdfInput.current.value = '';
    }
  };

  const submitScan = async () => {
    if (!session || session.status !== 'READY') return;
    setSubmitting(true); setError(null); setNotice(null);
    try {
      const queued = await scanBridgeClient.submitSession(session.sessionId);
      setSession(queued);
      setNotice('Package is safely queued. You can start the next physical package while delivery continues.');
    } catch (err) {
      const scanError = err as ScanBridgeError;
      if (isBridgeUnavailableError(scanError)) { setBridgeAvailable(false); setBridgeError(scanError.message); }
      else setError(scanError.message || 'Unable to queue this scan for submission.');
    } finally { setSubmitting(false); }
  };

  const startNext = () => {
    setSession(null);
    setIdentifierValues({});
    setTouched({});
    setError(null);
    setNotice(null);
  };

  const bridgeState = bridgeConfig && !bridgeConfig.enabled
    ? { tone: 'is-offline', label: 'Bridge disabled' }
    : bridgeAvailable === false
      ? { tone: 'is-offline', label: 'Bridge unavailable' }
      : bridgeConfig && bridgeAvailable === true
        ? { tone: 'is-online', label: 'Bridge ready' }
        : null;

  const isSimulator = bridgeConfig?.captureMode === 'SIMULATOR';
  const scannerLabel = isSimulator ? 'Test simulator' : 'Scanner';
  const lifecycleIndex = lifecycleIndexFor(session);
  const attention = session?.status === 'ATTENTION_REQUIRED';
  const lifecycleState = (index: number): LifecycleState => {
    if (index === 0 && !session) return identifiersReady ? 'complete' : 'current';
    if (index < lifecycleIndex) return 'complete';
    if (index === lifecycleIndex) return attention ? 'attention' : 'current';
    return 'upcoming';
  };

  const startBlocker = !session && !canStart && !loading
    ? bridgeAvailable !== true ? 'Scanner Bridge is not reachable from this workstation.'
      : bridgeConfig && !bridgeConfig.enabled ? 'Scanner Bridge is disabled for this site.'
        : !acquisitionAvailable ? 'No scanner is available for acquisition.'
          : !storageHealthy ? 'Workstation storage needs attention.'
            : scannerStatus?.busy ? 'The scanner is busy with another job.'
              : !identifiersReady ? `Complete ${requiredDefinitions.length - requiredComplete} required field${requiredDefinitions.length - requiredComplete === 1 ? '' : 's'} to start.`
                : null
    : null;

  const deckTone = attention ? 'is-attention'
    : session?.status === 'SUBMITTED' ? 'is-done'
      : session?.status === 'READY' ? 'is-ready'
        : session?.status === 'WAITING_FOR_SCAN' ? (bridgeAvailable === false ? 'is-disconnected' : 'is-scanning')
          : session ? 'is-queued' : 'is-idle';

  return (
    <PageScaffold className="scan-intake-page" breadcrumbs={[{ label: 'Scanner Intake' }]} title="Scan Documents"
      eyebrow="Scanner intake"
      description="Identify the physical package, capture it as one consolidated PDF, and hand it off durably."
      actions={bridgeState ? (
        <div className={`scan-bridge-state ${bridgeState.tone}`} role="status">
          <span className="scan-bridge-dot" aria-hidden="true" />{bridgeState.label}
        </div>
      ) : undefined}>

      <div className="scan-alerts" aria-live="polite">
        {bridgeError && <div className="ui-alert tone-danger">{bridgeError}</div>}
        {bridgeAvailable === false && <div className="scan-capability-note">Physical scanner acquisition runs on a workstation with the Scanner Bridge. Tablet and iPad users can still view package status and continue other browser workflows.</div>}
        {scannerStatus && !scannerStatus.storageHealthy && (
          <div className="ui-alert tone-danger">Scanner workstation storage needs attention before a new package can be scanned.</div>
        )}
        {error && <div className="ui-alert tone-danger">{error}</div>}
        {notice && <div className="ui-alert tone-info">{notice}</div>}
      </div>

      <div className="scan-station">
        <section className="ui-card scan-manifest" aria-labelledby="scan-manifest-title">
          <header className="scan-panel-head">
            <div>
              <h2 id="scan-manifest-title">Package identity</h2>
              <p>Defined by your Business Admin and re-validated by {branding.applicationName} on submission.</p>
            </div>
            {definitions.length > 0 && (
              <span className={`scan-readiness ${identifiersReady ? 'is-ready' : ''}`}>
                {identifiersReady ? <><Icon name="check" width={14} height={14} />Ready</> : `${requiredComplete} of ${requiredDefinitions.length} required`}
              </span>
            )}
          </header>

          {loading ? (
            <div className="scan-field-skeleton" aria-busy="true" aria-label="Loading intake requirements">
              <span /><span /><span />
            </div>
          ) : definitions.length === 0 ? (
            <div className="ui-alert tone-danger">No business identifier definitions are configured for this tenant.</div>
          ) : (
            <div className="scan-field-list">
              {definitions.map((definition, index) => {
                const fieldId = `scan-id-${definition.key}`;
                const fieldError = identifierErrors[definition.key];
                const showError = !!fieldError && (touched[definition.key] || !!identifierValues[definition.key]);
                const valid = !fieldError && !!identifierValues[definition.key];
                return (
                  <div className={`scan-field ${showError ? 'has-error' : ''} ${valid ? 'is-valid' : ''}`} key={definition.key}>
                    <label htmlFor={fieldId}>
                      {definition.label}
                      {definition.required ? <span className="scan-required" aria-hidden="true">Required</span> : <span className="scan-optional">Optional</span>}
                    </label>
                    <div className="scan-field-control">
                      {definition.allowedValues.length > 0 ? (
                        <select id={fieldId} value={identifierValues[definition.key] || ''} disabled={!!session}
                          aria-invalid={showError} aria-describedby={showError ? `${fieldId}-error` : undefined}
                          onBlur={() => setTouched((current) => ({ ...current, [definition.key]: true }))}
                          onChange={(event) => setIdentifierValues((current) => ({ ...current, [definition.key]: event.target.value }))}>
                          <option value="">Select…</option>
                          {definition.allowedValues.map((value) => <option key={value} value={value}>{value}</option>)}
                        </select>
                      ) : (
                        <input id={fieldId} value={identifierValues[definition.key] || ''} disabled={!!session}
                          autoFocus={index === 0 && !session} autoComplete="off" spellCheck={false}
                          className={index === 0 ? 'is-primary-identifier' : undefined}
                          aria-invalid={showError} aria-describedby={showError ? `${fieldId}-error` : undefined}
                          onBlur={() => setTouched((current) => ({ ...current, [definition.key]: true }))}
                          onChange={(event) => setIdentifierValues((current) => ({ ...current, [definition.key]: event.target.value }))} />
                      )}
                      {valid && <Icon name="check" className="scan-field-check" width={16} height={16} />}
                    </div>
                    {showError && <span className="field-error" id={`${fieldId}-error`}>{fieldError}</span>}
                  </div>
                );
              })}
            </div>
          )}

          {session && (
            <p className="scan-locked-note"><Icon name="shield" width={14} height={14} />Identity is locked to this scan session.</p>
          )}
        </section>

        <section className={`ui-card scan-deck ${deckTone}`} aria-labelledby="scan-deck-title">
          <header className="scan-deck-head">
            <div className="scan-device">
              <span className="scan-device-icon" aria-hidden="true"><Icon name="scan" width={18} height={18} /></span>
              <div>
                <h2 id="scan-deck-title">{scannerLabel}</h2>
                <p className="scan-device-state">
                  <span className={acquisitionAvailable ? 'scan-dot is-on' : 'scan-dot is-off'} aria-hidden="true" />
                  {bridgeConfig ? (acquisitionAvailable ? 'Connected' : 'Unavailable') : loading ? 'Connecting…' : 'Not configured'}
                  {bridgeConfig && <span className="scan-device-meta">{bridgeConfig.scannerId}</span>}
                </p>
              </div>
            </div>
            {bridgeConfig && <span className="scan-mode-chip">{isSimulator ? 'Simulator' : 'Physical'}</span>}
          </header>

          <ol className="scan-lifecycle" aria-label="Scanner intake progress">
            {LIFECYCLE.map((step, index) => {
              const state = lifecycleState(index);
              return (
                <li key={step.key} className={`is-${state}`} aria-current={state === 'current' ? 'step' : undefined}>
                  <span className="scan-lifecycle-mark" aria-hidden="true">
                    {state === 'complete' ? <Icon name="check" width={12} height={12} /> : index + 1}
                  </span>
                  <span className="scan-lifecycle-text">
                    <strong>{step.label}</strong>
                    <small>{step.detail}</small>
                  </span>
                </li>
              );
            })}
          </ol>

          <div className="scan-stage" role="status" aria-live="polite">
            <div className="scan-tray" aria-hidden="true">
              <span className="scan-sheet" /><span className="scan-sheet" /><span className="scan-sheet" />
              <span className="scan-beam" />
            </div>

            {!session && (
              <div className="scan-stage-body">
                <h3>{canStart ? 'Ready when you are' : 'Load the package'}</h3>
                <p>{isSimulator
                  ? 'Simulator mode: start the scan first, then select the test PDF. Do not place a PDF in the simulator inbox before starting.'
                  : 'Place the complete paper package in the feeder. One scan job creates one consolidated PDF.'}</p>
                {bridgeConfig?.captureMode === 'PHYSICAL' && !acquisitionAvailable && (
                  <p className="scan-stage-hint">No compatible Windows WIA scanner is available. Check power, driver, and connection.</p>
                )}
                <div className="scan-stage-actions">
                  <button className="ui-button primary scan-start" disabled={!canStart} onClick={() => void startSession()}>
                    <Icon name="scan" width={16} height={16} />{starting ? 'Starting…' : 'Start scan'}
                  </button>
                  {startBlocker && <span className="scan-blocker">{startBlocker}</span>}
                </div>
              </div>
            )}

            {session?.status === 'WAITING_FOR_SCAN' && (
              <div className="scan-stage-body">
                <span className="scan-stage-tag">{bridgeAvailable === false ? 'Disconnected' : 'Scanning'}</span>
                <h3>{bridgeAvailable === false ? 'Scanner Bridge disconnected' : 'Waiting for the feeder'}</h3>
                {bridgeAvailable === false ? (
                  <p>The scan session is durably retained. Restart the local Scanner Bridge and this page will reconnect automatically.</p>
                ) : isSimulator ? (
                  <p>No hardware is needed in test mode. Select a PDF to simulate one completed physical scan.</p>
                ) : (
                  <p>Scan the complete package with the document feeder. {branding.applicationName} waits for the scanner to finish the consolidated PDF.</p>
                )}
                <div className="scan-stage-actions">
                  {isSimulator && bridgeAvailable !== false && (
                    <>
                      <input ref={testPdfInput} className="scan-hidden-file" type="file" accept="application/pdf,.pdf"
                        onChange={(event) => void stageTestPdf(event.target.files?.[0])} />
                      <button className="ui-button primary" disabled={staging} onClick={() => testPdfInput.current?.click()}>
                        <Icon name="upload" width={16} height={16} />{staging ? 'Receiving test PDF…' : 'Select test PDF'}
                      </button>
                    </>
                  )}
                  <button className="ui-button secondary" disabled={bridgeAvailable !== true} onClick={() => void cancelSession()}>Cancel scan</button>
                </div>
              </div>
            )}

            {session?.status === 'READY' && session.preparedScan && (
              <div className="scan-stage-body">
                <span className="scan-stage-tag">Document received</span>
                <div className="scan-file">
                  <span className="scan-file-icon" aria-hidden="true">PDF</span>
                  <div>
                    <h3>{session.preparedScan.fileName}</h3>
                    <p>{formatBytes(session.preparedScan.sizeBytes)} · Integrity verified · Ready for {branding.applicationName}</p>
                  </div>
                </div>
                <div className="scan-stage-actions">
                  <button className="ui-button primary" disabled={submitting || bridgeAvailable !== true} onClick={() => void submitScan()}>
                    {submitting ? 'Queueing…' : 'Submit package'}
                  </button>
                  <button className="ui-button secondary" disabled={submitting || bridgeAvailable !== true} onClick={() => void cancelSession()}>Discard</button>
                </div>
                <details className="scan-tech">
                  <summary>Technical details</summary>
                  <dl>
                    <div><dt>Submission ID</dt><dd><code>{session.submissionId}</code></dd></div>
                    <div><dt>Source document ID</dt><dd><code>{session.preparedScan.sourceDocumentId}</code></dd></div>
                    <div><dt>SHA-256</dt><dd><code>{session.preparedScan.sha256}</code></dd></div>
                  </dl>
                </details>
              </div>
            )}

            {(session?.status === 'QUEUED' || session?.status === 'SUBMITTING') && (
              <div className="scan-stage-body">
                <span className="scan-stage-tag">Safely queued</span>
                <h3>{session.status === 'SUBMITTING' ? `Sending to ${branding.applicationName}…` : 'Package queued for delivery'}</h3>
                <p>The local bridge owns this package durably. Start the next package without waiting for OCR, redaction, review, or export.</p>
                <div className="scan-stage-actions">
                  <button className="ui-button primary" onClick={startNext}>Start next package</button>
                  {session.lexavaultPackageId && <Link className="ui-button secondary" to={`/packages/${session.lexavaultPackageId}`}>View package</Link>}
                </div>
              </div>
            )}

            {session?.status === 'SUBMITTED' && (
              <div className="scan-stage-body">
                <span className="scan-stage-tag">Accepted</span>
                <h3>Package submitted</h3>
                <p>Processing continues through the normal {branding.applicationName} workflow. Any required review appears in Manual Review.</p>
                <div className="scan-stage-actions">
                  <button className="ui-button primary" onClick={startNext}>Start next package</button>
                  {session.lexavaultPackageId && <Link className="ui-button secondary" to={`/packages/${session.lexavaultPackageId}`}>View package</Link>}
                </div>
              </div>
            )}

            {attention && (
              <div className="scan-stage-body">
                <span className="scan-stage-tag">Delivery needs attention</span>
                <h3>Your scanned PDF is safely retained locally</h3>
                <p>Technical support can resolve the delivery issue. You may continue with the next physical package.</p>
                {session.lastDeliveryErrorCode && <p className="scan-safe-code">Reference {session.lastDeliveryErrorCode}</p>}
                <div className="scan-stage-actions">
                  <button className="ui-button primary" onClick={startNext}>Start next package</button>
                </div>
              </div>
            )}
          </div>

          <footer className="scan-deck-foot">
            <div className="scan-queue">
              <span><strong>{scannerStatus?.queuedCount ?? 0}</strong> delivering</span>
              <span className={scannerStatus && scannerStatus.attentionRequiredCount > 0 ? 'is-attention' : ''}>
                <strong>{scannerStatus?.attentionRequiredCount ?? 0}</strong> need attention
              </span>
              {scannerStatus?.usableSpaceBytes != null && scannerStatus.usableSpaceBytes >= 0 && (
                <span><strong>{formatBytes(scannerStatus.usableSpaceBytes)}</strong> free</span>
              )}
            </div>
            {bridgeConfig && (
              <details className="scan-device-details">
                <summary>Device details</summary>
                <dl>
                  <div><dt>Mode</dt><dd>{bridgeConfig.captureMode}</dd></div>
                  <div><dt>Site</dt><dd>{bridgeConfig.siteId}</dd></div>
                  <div><dt>Scanner</dt><dd>{bridgeConfig.scannerId}</dd></div>
                  <div><dt>Bridge</dt><dd>{bridgeConfig.bridgeId}</dd></div>
                  <div><dt>Version</dt><dd>{bridgeConfig.bridgeVersion}</dd></div>
                  <div><dt>Instance</dt><dd><code>{bridgeConfig.bridgeInstanceId.slice(0, 8)}</code></dd></div>
                  <div><dt>Max scan PDF</dt><dd>{formatBytes(bridgeConfig.maxScanPdfSizeBytes)}</dd></div>
                </dl>
              </details>
            )}
          </footer>
        </section>
      </div>
    </PageScaffold>
  );
}
