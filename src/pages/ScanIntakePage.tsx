import { useEffect, useMemo, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { apiClient } from '../api/client';
import { scanBridgeClient, type ScanBridgeConfiguration, type ScanBridgeError, type ScanSession, type ScannerStatus } from '../api/scanBridgeClient';
import type { ApiError, IdentifierDefinition } from '../types';
import './ScanIntakePage.css';
import { useBranding } from '../branding/BrandingContext';
import PageScaffold from '../components/ui/PageScaffold';
import WorkflowStepper from '../components/ui/WorkflowStepper';

const formatBytes = (bytes: number) => {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
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

export default function ScanIntakePage() {
  const { branding } = useBranding();
  const [definitions, setDefinitions] = useState<IdentifierDefinition[]>([]);
  const [bridgeConfig, setBridgeConfig] = useState<ScanBridgeConfiguration | null>(null);
  const [scannerStatus, setScannerStatus] = useState<ScannerStatus | null>(null);
  const [bridgeAvailable, setBridgeAvailable] = useState<boolean | null>(null);
  const [identifierValues, setIdentifierValues] = useState<Record<string, string>>({});
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
    setError(null);
    setNotice(null);
  };

  const bridgeState = bridgeConfig && !bridgeConfig.enabled
    ? { className: 'is-offline', label: 'Bridge disabled' }
    : bridgeAvailable === false
      ? { className: 'is-offline', label: 'Bridge unavailable' }
      : bridgeConfig && bridgeAvailable === true
        ? { className: 'is-online', label: 'Bridge ready' }
        : null;

  const scannerLabel = bridgeConfig?.captureMode === 'SIMULATOR' ? 'Test simulator' : 'Scanner';

  return (
    <PageScaffold className="scan-intake-page" breadcrumbs={[{ label: 'Scanner Intake' }]} title="Scan Documents"
      eyebrow="Scanner intake"
      description="Identify the package, capture one consolidated PDF, and hand it off durably for processing."
      actions={bridgeState ? <div className={`scan-bridge-state ${bridgeState.className}`}>{bridgeState.label}</div> : undefined}>

      <WorkflowStepper
        ariaLabel="Scanner intake progress"
        steps={[
          { label: 'Identify package', description: 'Business fields', state: session || identifiersReady ? 'complete' : 'current' },
          { label: 'Capture document', description: 'One consolidated PDF', state: !session ? 'upcoming' : session.status === 'WAITING_FOR_SCAN' ? 'current' : 'complete' },
          { label: 'Queue & continue', description: 'Durable handoff', state: session?.status === 'READY' ? 'current' : session && ['QUEUED', 'SUBMITTING', 'SUBMITTED', 'ATTENTION_REQUIRED'].includes(session.status) ? 'complete' : 'upcoming' },
        ]}
      />

      {bridgeError && <div className="ui-alert tone-danger">{bridgeError}</div>}
      {bridgeAvailable === false && <div className="scan-device-capability-note">Physical scanner acquisition runs on a workstation with the Scanner Bridge. Tablet and iPad users can still view package status and continue other browser workflows.</div>}
      {scannerStatus && !scannerStatus.storageHealthy && (
        <div className="ui-alert tone-danger">Scanner workstation storage needs attention before a new package can be scanned.</div>
      )}
      {error && <div className="ui-alert tone-danger">{error}</div>}
      {notice && <div className="ui-alert tone-info">{notice}</div>}
      {scannerStatus && (scannerStatus.queuedCount > 0 || scannerStatus.attentionRequiredCount > 0) && (
        <div className="scan-queue-summary">
          {scannerStatus.queuedCount > 0 && <span>{scannerStatus.queuedCount} scanner package{scannerStatus.queuedCount === 1 ? '' : 's'} delivering</span>}
          {scannerStatus.attentionRequiredCount > 0 && <span className="is-attention">{scannerStatus.attentionRequiredCount} need technical attention</span>}
        </div>
      )}

      <div className="scan-intake-grid">
        <section className="ui-card scan-intake-card">
          <h2>1. Business information</h2>
          <p className="scan-intake-help">Fields are controlled by this tenant's Business Admin configuration and are validated again by {branding.applicationName} on submission.</p>
          {loading ? <p>Loading tenant intake requirements…</p> : definitions.length === 0 ? (
            <div className="ui-alert tone-danger">No business identifier definitions are configured for this tenant.</div>
          ) : (
            <div className="scan-field-grid">
              {definitions.map((definition) => (
                <div className="form-group" key={definition.key}>
                  <label htmlFor={`scan-id-${definition.key}`}>{definition.label}{definition.required ? ' *' : ''}</label>
                  {definition.allowedValues.length > 0 ? (
                    <select id={`scan-id-${definition.key}`} value={identifierValues[definition.key] || ''} disabled={!!session}
                      onChange={(event) => setIdentifierValues((current) => ({ ...current, [definition.key]: event.target.value }))}>
                      <option value="">Select…</option>
                      {definition.allowedValues.map((value) => <option key={value} value={value}>{value}</option>)}
                    </select>
                  ) : (
                    <input id={`scan-id-${definition.key}`} value={identifierValues[definition.key] || ''} disabled={!!session}
                      onChange={(event) => setIdentifierValues((current) => ({ ...current, [definition.key]: event.target.value }))} />
                  )}
                  {identifierErrors[definition.key] && <span className="field-error">{identifierErrors[definition.key]}</span>}
                </div>
              ))}
            </div>
          )}
        </section>

        <section className="ui-card scan-intake-card">
          <h2>2. Scanner</h2>
          {bridgeConfig ? (
            <>
              <div className="scan-connected-row">
                <span className={acquisitionAvailable ? 'scan-connected-dot' : 'scan-offline-dot'} />
                <strong>{scannerLabel}: {acquisitionAvailable ? 'Connected' : 'Unavailable'}</strong>
              </div>
              <details className="scan-device-details">
                <summary>Scanner details</summary>
                <div className="scan-device-summary">
                  <div><span>Mode</span><strong>{bridgeConfig.captureMode}</strong></div>
                  <div><span>Site</span><strong>{bridgeConfig.siteId}</strong></div>
                  <div><span>Scanner</span><strong>{bridgeConfig.scannerId}</strong></div>
                  <div><span>Bridge</span><strong>{bridgeConfig.bridgeId}</strong></div>
                  <div><span>Bridge version</span><strong>{bridgeConfig.bridgeVersion}</strong></div>
                  <div><span>Bridge instance</span><code>{bridgeConfig.bridgeInstanceId.slice(0, 8)}</code></div>
                  {scannerStatus?.usableSpaceBytes != null && scannerStatus.usableSpaceBytes >= 0 && (
                    <div><span>Local storage available</span><strong>{formatBytes(scannerStatus.usableSpaceBytes)}</strong></div>
                  )}
                  <div><span>Maximum scan PDF</span><strong>{formatBytes(bridgeConfig.maxScanPdfSizeBytes)}</strong></div>
                </div>
              </details>
            </>
          ) : !loading && <p>Scanner Bridge configuration is unavailable.</p>}

          {!session && (
            <div className="scan-action-panel">
              <p>{bridgeConfig?.captureMode === 'SIMULATOR'
                ? 'Simulator mode: click Start Scan first, then select the test PDF. Do not place a PDF in the simulator inbox before starting the session.'
                : 'Load the complete paper package into the scanner. One scan job creates one consolidated PDF.'}</p>
              {bridgeConfig?.captureMode === 'PHYSICAL' && !acquisitionAvailable && (
                <p className="scan-intake-help">No compatible Windows WIA scanner is currently available. Check the scanner power, Windows driver, and connection.</p>
              )}
              <button className="ui-button primary" disabled={!canStart} onClick={() => void startSession()}>{starting ? 'Starting…' : 'Start Scan'}</button>
            </div>
          )}

          {session?.status === 'WAITING_FOR_SCAN' && (
            <div className={`scan-waiting-panel${bridgeAvailable === false ? ' is-disconnected' : ''}`} role="status" aria-live="polite">
              <div className={bridgeAvailable === false ? 'scan-disconnected-indicator' : 'scan-pulse'} aria-hidden="true" />
              <h3>{bridgeAvailable === false ? 'Scanner Bridge disconnected' : 'Waiting for scan'}</h3>
              {bridgeAvailable === false ? (
                <p>The scan session is durably retained. Restart the local Scanner Bridge and this page will reconnect automatically.</p>
              ) : bridgeConfig?.captureMode === 'SIMULATOR' ? (
                <>
                  <p>No scanner hardware is required in test mode. Select a PDF to simulate one completed physical scan.</p>
                  <input ref={testPdfInput} className="scan-hidden-file" type="file" accept="application/pdf,.pdf"
                    onChange={(event) => void stageTestPdf(event.target.files?.[0])} />
                  <button className="ui-button secondary" disabled={staging} onClick={() => testPdfInput.current?.click()}>
                    {staging ? 'Receiving Test PDF…' : 'Select Test PDF'}
                  </button>
                </>
              ) : (
                <p>Scan the complete package using the connected document feeder. {branding.applicationName} will wait for the scanner to finish the consolidated PDF.</p>
              )}
              <button className="ui-button secondary scan-cancel-button" disabled={bridgeAvailable !== true} onClick={() => void cancelSession()}>Cancel</button>
            </div>
          )}

          {session?.status === 'READY' && session.preparedScan && (
            <div className="scan-ready-panel">
              <div className="scan-ready-badge">DOCUMENT RECEIVED</div>
              <h3>{session.preparedScan.fileName}</h3>
              <p><strong>{formatBytes(session.preparedScan.sizeBytes)}</strong> · Ready to submit to {branding.applicationName}</p>
              <div className="scan-primary-actions">
                <button className="ui-button primary" disabled={submitting || bridgeAvailable !== true} onClick={() => void submitScan()}>
                  {submitting ? 'Queueing…' : 'Submit'}
                </button>
                <button className="ui-button secondary" disabled={submitting || bridgeAvailable !== true} onClick={() => void cancelSession()}>Cancel</button>
              </div>
              <details className="scan-technical-details">
                <summary>Technical details</summary>
                <div className="scan-technical-grid">
                  <div><span>Submission ID</span><code>{session.submissionId}</code></div>
                  <div><span>Source Document ID</span><code>{session.preparedScan.sourceDocumentId}</code></div>
                  <div><span>SHA-256</span><code>{session.preparedScan.sha256}</code></div>
                </div>
              </details>
            </div>
          )}

          {(session?.status === 'QUEUED' || session?.status === 'SUBMITTING') && (
            <div className="scan-queued-panel">
              <div className="scan-ready-badge">SAFELY QUEUED</div>
              <h3>{session.status === 'SUBMITTING' ? `Sending to ${branding.applicationName}…` : 'Package queued for delivery'}</h3>
              <p>The local bridge owns this package durably. You can start the next package without waiting for OCR, redaction, review, or export.</p>
              <div className="scan-primary-actions">
                <button className="ui-button primary" onClick={startNext}>Start Next Package</button>
                {session.lexavaultPackageId && <Link className="ui-button secondary" to={`/packages/${session.lexavaultPackageId}`}>View Package</Link>}
              </div>
            </div>
          )}

          {session?.status === 'SUBMITTED' && (
            <div className="scan-ready-panel">
              <div className="scan-ready-badge">ACCEPTED BY LEXAVAULT</div>
              <h3>Package submitted successfully</h3>
              <p>Processing continues through the normal {branding.applicationName} workflow. Any required review will appear in the existing Manual Review flow.</p>
              <div className="scan-primary-actions">
                <button className="ui-button primary" onClick={startNext}>Start Next Package</button>
                {session.lexavaultPackageId && <Link className="ui-button secondary" to={`/packages/${session.lexavaultPackageId}`}>View Package</Link>}
              </div>
            </div>
          )}

          {session?.status === 'ATTENTION_REQUIRED' && (
            <div className="scan-attention-panel">
              <div className="scan-attention-badge">DELIVERY NEEDS ATTENTION</div>
              <h3>Your scanned PDF is still safely retained locally</h3>
              <p>Technical support can resolve the delivery issue. You may continue with the next physical package.</p>
              {session.lastDeliveryErrorCode && <p className="scan-safe-code">Reference: {session.lastDeliveryErrorCode}</p>}
              <button className="ui-button primary" onClick={startNext}>Start Next Package</button>
            </div>
          )}
        </section>
      </div>
    </PageScaffold>
  );
}
