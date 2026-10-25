import { useEffect, useRef, useState } from 'react';
import useSWR, { useSWRConfig } from 'swr';
import { Check, FileUp, Printer } from 'lucide-react';
import { scanBridgeClient, type ScanSession } from '../api/scanBridgeClient';
import { errorMessage, formatBytes, useScannerStatus } from './data';
import { Notice, Spinner } from './atoms';
import type { IdentityGate } from './Intake';

export default function ScanCapture({ gate, onLock, onCreated }: {
  gate: IdentityGate;
  onLock: (identity: Record<string, string> | null, reason: string) => void;
  onCreated: (packageId: string | null) => void;
}) {
  const fileRef = useRef<HTMLInputElement>(null);
  const { mutate: mutateGlobal } = useSWRConfig();
  const { data: scanner, error: scannerError } = useScannerStatus(true);
  const { data: session, mutate } = useSWR<ScanSession | undefined>(
    'scan-session',
    () => scanBridgeClient.getCurrentSession(),
    { refreshInterval: (latest) => (latest ? 1500 : 0) },
  );
  const [busy, setBusy] = useState<'start' | 'stage' | 'submit' | 'cancel' | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const busyRef = useRef(false);

  const sessionId = session?.sessionId;
  useEffect(() => {
    onLock(session ? session.businessIdentifiers : null, 'the active scan session');
    // Lock only changes when a session starts or ends, not on every poll.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sessionId, onLock]);

  const act = async (kind: NonNullable<typeof busy>, work: () => Promise<void>) => {
    if (busyRef.current) return;
    busyRef.current = true;
    setBusy(kind);
    setFailure(null);
    try {
      await work();
    } catch (err) {
      setFailure(errorMessage(err, 'The scanner did not respond. Nothing was submitted.'));
      await mutate();
    } finally {
      busyRef.current = false;
      setBusy(null);
    }
  };

  const start = () => act('start', async () => {
    const identity = gate.require();
    if (!identity) throw new Error('Complete the case identity first.');
    await mutate(await scanBridgeClient.startSession(identity), { revalidate: false });
  });

  const stage = (file: File) => act('stage', async () => {
    if (!session) return;
    await scanBridgeClient.stageSimulatorPdf(session.sessionId, file);
    await mutate();
  });

  const submit = () => act('submit', async () => {
    if (!session) return;
    const result = await scanBridgeClient.submitSession(session.sessionId);
    await mutate(undefined, { revalidate: false });
    await mutateGlobal('packages');
    onCreated(result.lexavaultPackageId ?? null);
  });

  const cancel = () => act('cancel', async () => {
    if (!session) return;
    await scanBridgeClient.cancelSession(session.sessionId);
    await mutate(undefined, { revalidate: false });
  });

  const ready = scanner?.acquisitionAvailable && scanner.storageHealthy;
  const waiting = session?.status === 'WAITING_FOR_SCAN';
  const prepared = session?.status === 'READY' ? session.preparedScan : null;
  const simulator = session?.captureMode === 'SIMULATOR' || scanner?.captureMode === 'SIMULATOR';

  return (
    <div className="cf-capture">
      <div className="cf-scanner" data-ready={ready || undefined}>
        <Printer size={18} aria-hidden="true" />
        <div className="cf-scanner-text">
          <span className="cf-scanner-name">{scanner ? `${scanner.scannerId.toUpperCase()} · ${scanner.siteId}` : 'Scanner'}</span>
          <span className="cf-doc-meta">
            {scannerError ? 'Bridge unreachable' : !scanner ? 'Checking…' : ready ? `Ready · ${formatBytes(scanner.usableSpaceBytes)} free` : !scanner.acquisitionAvailable ? 'Offline' : 'Storage unavailable'}
            {simulator && ' · Simulator'}
          </span>
        </div>
        <span className="cf-scanner-dot" aria-hidden="true" />
      </div>

      {!session && (scannerError || (scanner && !ready)) && (
        <Notice tone="danger">
          {scannerError
            ? 'The scan bridge on this workstation is not responding. Use Upload instead, or ask IT to restart the bridge.'
            : !scanner?.acquisitionAvailable
              ? 'The scanner is not reachable. Check that it is powered on and connected, then it will reappear here. You can upload PDFs instead.'
              : 'The bridge cannot store scans right now. Use Upload instead.'}
        </Notice>
      )}

      {!session && (
        <div className="cf-feeder">
          <div className="cf-feeder-slot" aria-hidden="true"><span className="cf-feeder-sheet" /></div>
          <p className="cf-drop-title">Load the pages face down</p>
          <p className="cf-drop-text">Starting a session reserves the scanner and ties every page to this case.</p>
        </div>
      )}

      {waiting && (
        <div className="cf-feeder" data-active>
          <div className="cf-feeder-slot" aria-hidden="true"><span className="cf-feeder-sheet" /><span className="cf-feeder-beam" /></div>
          <p className="cf-drop-title">Waiting for the scanner</p>
          <p className="cf-drop-text">
            {simulator ? 'This bridge runs in simulator mode. Feed it a PDF to stand in for the paper.' : 'Press scan on the device. Pages arrive here automatically.'}
          </p>
          {simulator && (
            <>
              <button type="button" className="cf-btn" data-variant="ghost" data-size="sm" onClick={() => fileRef.current?.click()} disabled={busy !== null}>
                {busy === 'stage' ? <Spinner label="Feeding" /> : <FileUp size={14} aria-hidden="true" />} Feed a PDF
              </button>
              <input ref={fileRef} type="file" accept="application/pdf,.pdf" className="cf-sr" tabIndex={-1}
                onChange={(e) => { const f = e.target.files?.[0]; if (f) stage(f); e.target.value = ''; }} />
            </>
          )}
        </div>
      )}

      {prepared && (
        <div className="cf-scan-result">
          <div className="cf-scan-thumb" aria-hidden="true">
            {Array.from({ length: Math.min(prepared.pageCount ?? 1, 4) }).map((_, i) => <span key={i} />)}
          </div>
          <div className="cf-scan-facts">
            <p className="cf-next-heading"><Check size={16} className="cf-ok" aria-hidden="true" /> Scan captured</p>
            <dl className="cf-facts">
              <div><dt>File</dt><dd>{prepared.fileName}</dd></div>
              <div><dt>Pages</dt><dd>{prepared.pageCount ?? '—'}</dd></div>
              <div><dt>Size</dt><dd>{formatBytes(prepared.sizeBytes)}</dd></div>
              <div><dt>Fingerprint</dt><dd className="cf-mono">{prepared.sha256.slice(0, 12)}…</dd></div>
            </dl>
          </div>
        </div>
      )}

      {failure && <Notice tone="danger">{failure}</Notice>}

      <div className="cf-capture-foot">
        {!session ? (
          <>
            <p className="cf-muted">{gate.valid ? 'Ready to reserve the scanner.' : 'Complete the identity to continue.'}</p>
            <button type="button" className="cf-btn" data-variant="solid" onClick={start} disabled={busy !== null || !ready}>
              {busy === 'start' ? <><Spinner label="Starting" /> Starting…</> : 'Start scan session'}
            </button>
          </>
        ) : (
          <>
            <button type="button" className="cf-btn" data-variant="ghost" onClick={cancel} disabled={busy !== null}>
              {busy === 'cancel' ? 'Cancelling…' : 'Cancel session'}
            </button>
            <button type="button" className="cf-btn" data-variant="solid" onClick={submit} disabled={busy !== null || !prepared}>
              {busy === 'submit' ? <><Spinner label="Submitting" /> Submitting…</> : 'Open case & submit'}
            </button>
          </>
        )}
      </div>
    </div>
  );
}
