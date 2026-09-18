export type ScannerCaptureMode = 'SIMULATOR' | 'PHYSICAL';

export interface ScanBridgeConfiguration {
  enabled: boolean;
  tenantId: string;
  bridgeId: string;
  siteId: string;
  scannerId: string;
  captureMode: ScannerCaptureMode;
  bridgeInstanceId: string;
  bridgeVersion: string;
  simulatorInboxPath?: string | null;
  stableWindowMs: number;
  maxScanPdfSizeBytes: number;
  minimumUsableSpaceBytes: number;
  prohibitedCapabilities: string[];
}

interface ScanBridgeConfigurationWire extends ScanBridgeConfiguration {
  /** Per-process browser/loopback anti-CSRF nonce. Consumed only inside this API client. */
  browserRequestToken: string;
}

export interface ScannerStatus {
  enabled: boolean;
  bridgeId: string;
  siteId: string;
  scannerId: string;
  captureMode: ScannerCaptureMode;
  bridgeInstanceId: string;
  bridgeVersion: string;
  acquisitionAvailable: boolean;
  storageHealthy: boolean;
  usableSpaceBytes: number;
  minimumUsableSpaceBytes: number;
  maxScanPdfSizeBytes: number;
  busy: boolean;
  activeSessionId?: string | null;
  queuedCount: number;
  attentionRequiredCount: number;
  checkedAt: string;
}

export interface PreparedScan {
  sourceDocumentId: string;
  fileName: string;
  sizeBytes: number;
  sha256: string;
  receivedAt: string;
  workReference: string;
  pageCount?: number | null;
}

export type ScanSessionStatus =
  | 'WAITING_FOR_SCAN'
  | 'READY'
  | 'QUEUED'
  | 'SUBMITTING'
  | 'SUBMITTED'
  | 'ATTENTION_REQUIRED'
  | 'CANCELLED';

export interface ScanSession {
  sessionId: string;
  submissionId: string;
  tenantId: string;
  bridgeId: string;
  siteId: string;
  scannerId: string;
  captureMode: ScannerCaptureMode;
  status: ScanSessionStatus;
  businessIdentifiers: Record<string, string>;
  createdAt: string;
  updatedAt: string;
  preparedScan?: PreparedScan | null;
  lexavaultPackageId?: string | null;
  deliveryAttemptCount: number;
  queuedAt?: string | null;
  nextDeliveryAttemptAt?: string | null;
  lastDeliveryErrorCode?: string | null;
  lastDeliveryErrorAt?: string | null;
  submittedAt?: string | null;
}

export interface ScannerDeliveryItem {
  sessionId: string;
  submissionId: string;
  sourceDocumentId?: string | null;
  status: ScanSessionStatus;
  captureMode: ScannerCaptureMode;
  bridgeId: string;
  siteId: string;
  scannerId: string;
  lexavaultPackageId?: string | null;
  sizeBytes: number;
  deliveryAttemptCount: number;
  queuedAt?: string | null;
  nextDeliveryAttemptAt?: string | null;
  lastDeliveryErrorCode?: string | null;
  lastDeliveryErrorAt?: string | null;
  submittedAt?: string | null;
}

export interface ScanBridgeError {
  errorCode?: string;
  message: string;
  source?: 'scanBridge';
  httpStatus?: number;
}

const baseUrl = (import.meta.env.VITE_SCAN_BRIDGE_BASE_URL || 'http://127.0.0.1:10090/api/v1').replace(/\/$/, '');
const BRIDGE_TOKEN_HEADER = 'X-LexaVault-Bridge-Token';
const MUTATING_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);
let browserRequestToken: string | null = null;

function isMutating(init?: RequestInit): boolean {
  return MUTATING_METHODS.has((init?.method || 'GET').toUpperCase());
}

function networkUnavailable(): ScanBridgeError {
  return {
    message: 'Local Scanner Bridge is unavailable. Start the bridge service and try again.',
    source: 'scanBridge',
  };
}

async function execute<T>(path: string, init?: RequestInit, allowTokenRefresh = true): Promise<T> {
  const mutating = isMutating(init);
  const isForm = typeof FormData !== 'undefined' && init?.body instanceof FormData;
  const headers = new Headers(init?.headers || {});
  if (init?.body != null && !isForm && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json');
  if (mutating && browserRequestToken) headers.set(BRIDGE_TOKEN_HEADER, browserRequestToken);

  let response: Response;
  try {
    response = await fetch(`${baseUrl}${path}`, { ...init, headers, cache: 'no-store' });
  } catch {
    // GETs may still work when the browser blocks a preflighted POST. Distinguish that case from
    // an actually stopped bridge so the UI does not incorrectly flip to "Bridge unavailable".
    if (mutating) {
      try {
        const probe = await fetch(`${baseUrl}/scanner-status`, { cache: 'no-store' });
        if (probe.ok) {
          throw {
            errorCode: 'BRIDGE_BROWSER_REQUEST_BLOCKED',
            message: 'Scanner Bridge is reachable, but the browser blocked the state-changing request. Restart the Scanner Bridge and reload this page.',
            source: 'scanBridge',
          } satisfies ScanBridgeError;
        }
      } catch (probeError) {
        const structured = probeError as ScanBridgeError;
        if (structured.errorCode === 'BRIDGE_BROWSER_REQUEST_BLOCKED') throw structured;
      }
    }
    throw networkUnavailable();
  }

  if (!response.ok) {
    let error: ScanBridgeError = {
      message: `Scanner Bridge returned HTTP ${response.status}.`,
      source: 'scanBridge',
      httpStatus: response.status,
    };
    try {
      const body = await response.json() as ScanBridgeError;
      error = { ...body, source: 'scanBridge', httpStatus: response.status };
    } catch {
      // Keep the safe generic error; bridge responses never expose file/document content.
    }

    // The token rotates on every Scanner Bridge restart. Refresh configuration once and retry the
    // original mutation so a recovered bridge does not force the user to reload the whole page.
    if (mutating && allowTokenRefresh && error.errorCode === 'BRIDGE_REQUEST_TOKEN_INVALID') {
      await refreshConfigurationToken();
      return execute<T>(path, init, false);
    }
    throw error;
  }

  if (response.status === 204) return undefined as T;
  const text = await response.text();
  if (!text) return undefined as T;
  return JSON.parse(text) as T;
}

async function refreshConfigurationToken(): Promise<ScanBridgeConfiguration> {
  const wire = await execute<ScanBridgeConfigurationWire>('/configuration', undefined, false);
  browserRequestToken = wire.browserRequestToken;
  // Keep the nonce out of React/component state and diagnostics. It lives only in this module.
  const { browserRequestToken: secret, ...configuration } = wire;
  void secret;
  return configuration;
}

export const scanBridgeClient = {
  getConfiguration: () => refreshConfigurationToken(),
  getScannerStatus: () => execute<ScannerStatus>('/scanner-status'),
  startSession: (businessIdentifiers: Record<string, string>) => execute<ScanSession>('/scan-sessions', {
    method: 'POST',
    body: JSON.stringify({ businessIdentifiers }),
  }),
  getCurrentSession: () => execute<ScanSession | undefined>('/scan-sessions/current'),
  getSession: (sessionId: string) => execute<ScanSession>(`/scan-sessions/${encodeURIComponent(sessionId)}`),
  cancelSession: (sessionId: string) => execute<ScanSession>(`/scan-sessions/${encodeURIComponent(sessionId)}/cancel`, { method: 'POST' }),
  submitSession: (sessionId: string) => execute<ScanSession>(`/scan-sessions/${encodeURIComponent(sessionId)}/submit`, { method: 'POST' }),
  listDeliveryItems: () => execute<ScannerDeliveryItem[]>('/delivery-items'),
  retryDelivery: (sessionId: string) => execute<ScanSession>(`/delivery-items/${encodeURIComponent(sessionId)}/retry`, { method: 'POST' }),
  stageSimulatorPdf: (sessionId: string, file: File) => {
    const form = new FormData();
    form.append('file', file);
    return execute<void>(`/scan-sessions/${encodeURIComponent(sessionId)}/simulator-pdf`, { method: 'POST', body: form });
  },
};
