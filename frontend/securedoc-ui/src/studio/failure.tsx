import { Component, type ErrorInfo, type ReactNode } from 'react';
import { useSWRConfig } from 'swr';
import { LvText } from './help/LvCodeLink';

/* ---------- Answers this screen does not understand ---------- */

/** An answer that could not be used: the server refused, the network failed, or the answer had the wrong shape. */
export class LoadFailure extends Error {
  constructor(
    message: string,
    readonly request: string,
    readonly reference: string,
    readonly kind: 'shape' | 'server' | 'render',
    readonly status?: number,
  ) {
    super(message);
    this.name = 'LoadFailure';
  }
}

export const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value);

export const isArrayOf = (item: (value: unknown) => boolean) => (value: unknown): boolean =>
  Array.isArray(value) && value.every(item);

/** A short reference for a failure found in the browser (no server correlation id exists for it). */
export function newReference(): string {
  const bytes = new Uint8Array(4);
  globalThis.crypto?.getRandomValues?.(bytes);
  return `ui-${Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')}`;
}

/**
 * Resolves the request and checks the shape of its answer. A wrong shape becomes a LoadFailure, so screens show the
 * "couldn't load" card instead of rendering data they do not understand.
 */
export async function checked<T>(request: string, answer: Promise<T>, isExpected: (value: unknown) => boolean): Promise<T> {
  const value = await answer;
  if (!isExpected(value)) {
    const reference = newReference();
    console.error(`[LexaVault] ${request} returned an answer this screen does not understand (reference ${reference}).`);
    throw new LoadFailure('unexpected response shape', request, reference, 'shape');
  }
  return value;
}

/** What a failure can tell IT: a reference (correlation id when the server gave one), the request and the cause. */
export function failureDetails(error: unknown, request?: string): { reference?: string; request?: string; cause: string; whatToDo?: string } {
  if (error instanceof LoadFailure) {
    return { reference: error.reference, request: error.request, cause: error.kind === 'shape' ? 'unexpected response shape' : error.message };
  }
  const e = (isRecord(error) ? error : {}) as { correlationId?: unknown; reference?: unknown; whatToDo?: unknown; httpStatus?: unknown; errorCode?: unknown; code?: unknown; message?: unknown };
  const status = typeof e.httpStatus === 'number' ? `HTTP ${e.httpStatus}` : undefined;
  // Task 0025: the LV code first, then the internal detail.
  const code = [typeof e.code === 'string' ? e.code : undefined, typeof e.errorCode === 'string' ? e.errorCode : undefined].filter(Boolean).join(' · ') || undefined;
  const message = error instanceof Error ? error.message : typeof e.message === 'string' ? e.message : undefined;
  return {
    // Task 0057: the reference (R-…) of the shared error shape first: it is the one in the log, on Support and in the bundle.
    reference: typeof e.reference === 'string' ? e.reference : typeof e.correlationId === 'string' ? e.correlationId : undefined,
    request,
    cause: [status, code, message].filter(Boolean).join(' · ') || 'unknown error',
    whatToDo: typeof e.whatToDo === 'string' && e.whatToDo.trim() ? e.whatToDo : undefined,
  };
}

/* ---------- Technical details ---------- */

/** One collapsed disclosure for codes and IDs, which never sit in the main view. */
export function TechnicalDetails({ children, label = 'Technical details' }: { children: ReactNode; label?: string }) {
  return (
    <details className="cf-tech">
      <summary><span className="cf-tech-label">{label}</span></summary>
      <div className="cf-tech-body">{children}</div>
    </details>
  );
}

/* ---------- The "couldn't load" card ---------- */

export function LoadErrorCard({ title, error, request, onRetry }: {
  /** For example "These settings couldn't be loaded". */
  title: string;
  error?: unknown;
  request?: string;
  onRetry: () => void;
}) {
  const details = failureDetails(error, request);
  const server = !(error instanceof LoadFailure) || error.kind === 'server';
  const sentence = error instanceof LoadFailure && error.kind === 'render'
    ? 'Something on this part of the screen went wrong. Nothing was changed. Try again; if it keeps happening, send the reference below to your IT team.'
    : server
      ? 'LexaVault’s server could not answer. Nothing was changed. Try again in a minute; if it keeps happening, send the reference below to your IT team.'
      : 'LexaVault’s server sent an answer this screen doesn’t understand. Nothing was changed. Try again in a minute; if it keeps happening, send the reference below to your IT team.';
  return (
    <section className="cf-loaderr" role="alert" aria-label={title}>
      <div className="cf-loaderr-head">
        <div>
          <h2 className="cf-loaderr-title">{title}</h2>
          <p className="cf-loaderr-text">{sentence}</p>
        </div>
        <button type="button" className="cf-btn" data-size="sm" onClick={onRetry}>Try again</button>
      </div>
      <TechnicalDetails>
        <dl className="cf-tech-defs">
          {details.reference && <div><dt>Reference</dt><dd className="cf-mono">{details.reference}</dd></div>}
          {details.request && <div><dt>Request</dt><dd className="cf-mono">{details.request}</dd></div>}
          <div><dt>Cause</dt><dd className="cf-mono"><LvText>{details.cause}</LvText></dd></div>
          {details.whatToDo && <div><dt>What to do</dt><dd><LvText>{details.whatToDo}</LvText></dd></div>}
        </dl>
      </TechnicalDetails>
    </section>
  );
}

/* ---------- Error boundary ---------- */

interface BoundaryProps {
  title: string;
  /** Shown in Technical details, for example the route. */
  request?: string;
  /** A change of key clears the failure (for example a new route). */
  resetKey?: string;
  /** Called by Try again after the boundary clears; usually revalidates the data. */
  onRetry?: () => void;
  children: ReactNode;
}

interface BoundaryState { failure: LoadFailure | null; resetKey?: string }

class Boundary extends Component<BoundaryProps, BoundaryState> {
  state: BoundaryState = { failure: null, resetKey: this.props.resetKey };

  static getDerivedStateFromError(error: unknown): Partial<BoundaryState> {
    const failure = error instanceof LoadFailure
      ? error
      : new LoadFailure(error instanceof Error ? error.message : 'render failed', '', newReference(), 'render');
    return { failure };
  }

  static getDerivedStateFromProps(props: BoundaryProps, state: BoundaryState): Partial<BoundaryState> | null {
    return props.resetKey !== state.resetKey ? { failure: null, resetKey: props.resetKey } : null;
  }

  componentDidCatch(error: unknown, info: ErrorInfo) {
    const reference = this.state.failure?.reference;
    console.error(`[LexaVault] ${this.props.title} failed to render (reference ${reference}).`, error, info.componentStack);
  }

  retry = () => {
    this.setState({ failure: null });
    this.props.onRetry?.();
  };

  render() {
    const { failure } = this.state;
    if (!failure) return this.props.children;
    return <LoadErrorCard title={this.props.title} error={failure} request={failure.request || this.props.request} onRetry={this.retry} />;
  }
}

/**
 * Keeps a failure inside one screen or card: the top bar and every other screen keep working. Try again clears the
 * failure and revalidates every loaded answer in this workspace.
 */
export function ErrorBoundary(props: Omit<BoundaryProps, 'onRetry'> & { onRetry?: () => void }) {
  const { mutate } = useSWRConfig();
  const onRetry = props.onRetry ?? (() => { void mutate(() => true); });
  return <Boundary {...props} onRetry={onRetry} />;
}
