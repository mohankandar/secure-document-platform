import { useCallback, useEffect, useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { apiClient } from '../api/client';
import type { ApprovalAnswer } from '../types';
import { CAP, errorMessage } from './data';
import { Notice, Spinner } from './atoms';
import { when } from './admin/shared';
import { DECLINE_REASONS, approvalToken } from './admin/notificationRules';
import { approvalLinkHeld, forgetApprovalLink, holdApprovalLink } from './approvalLinkStore';

/**
 * The page an approval link opens (task 0059). Opening it decides nothing: it asks the server what the link is for, and one more
 * click here sends the decision. The token is taken out of the address at once (so it is in no history and no referrer) and kept
 * in this tab only until the decision.
 */
export default function ApprovalLink({ caps, entityName }: { caps: string[]; entityName?: string | null }) {
  const location = useLocation();
  const navigate = useNavigate();
  const [token, setToken] = useState<string | null>(() => approvalToken(location.pathname) ?? approvalLinkHeld());
  const [answer, setAnswer] = useState<ApprovalAnswer | null>(null);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const allowed = caps.includes(CAP.approve);

  useEffect(() => {
    const fromPath = approvalToken(location.pathname);
    if (fromPath) { holdApprovalLink(fromPath); setToken(fromPath); }
    if (location.pathname !== '/approvals/link') navigate('/approvals/link', { replace: true });
  }, [location.pathname, navigate]);

  const open = useCallback(async (t: string) => {
    setProblem(null);
    try { setAnswer(await apiClient.openApprovalLink(t)); } catch (e) { setProblem(errorMessage(e, 'The link could not be checked.')); }
  }, []);
  useEffect(() => { if (token && allowed) void open(token); }, [token, allowed, open]);

  async function decide(action: 'APPROVE' | 'DECLINE') {
    if (!token) return;
    setBusy(true);
    setProblem(null);
    try {
      const result = await apiClient.decideApprovalLink(token, action, action === 'DECLINE' ? reason : null);
      setAnswer(result);
      if (result.state !== 'REFUSED') forgetApprovalLink();
    } catch (e) {
      setProblem(errorMessage(e, 'The decision could not be sent. Nothing was decided.'));
    } finally {
      setBusy(false);
    }
  }

  const declineFirst = answer?.action === 'DECLINE';
  return (
    <div className="cf-approval" aria-live="polite">
      <span className="cf-eyebrow">{entityName ?? 'Workspace'}</span>
      <h1 className="cf-title">Delivery approval</h1>
      {!allowed ? <Notice tone="mark">Only a Supervisor of this entity can use an approval link. If you are one in another entity, switch to it and open the link again.</Notice>
        : !token ? <Notice>Open the link in your message again, or approve the case on Cases.</Notice>
          : problem && !answer ? <><Notice tone="danger">{problem}</Notice><button type="button" className="cf-btn" data-size="sm" onClick={() => void open(token)}>Try again</button></>
            : !answer ? <p><Spinner label="Checking the link" /> Checking the link…</p>
              : answer.state === 'READY' ? (
                <section className="cf-ad-card cf-ad-stack" aria-labelledby="cf-ap-head">
                  <h2 id="cf-ap-head">{answer.caseNumber ? `Deliver case ${answer.caseNumber}?` : 'Deliver this case?'}</h2>
                  <dl className="cf-ad-facts">
                    <div><dt>Asked by</dt><dd>{answer.askedBy ?? 'An automatic source'}</dd></div>
                    <div><dt>Why</dt><dd>This entity’s rules ask for approval before redacted documents are delivered.</dd></div>
                    {answer.expiresAt && <div><dt>Link works until</dt><dd>{when(answer.expiresAt)}</dd></div>}
                  </dl>
                  <p><Link to={`/case/${answer.caseId}`}>Look at the case first</Link></p>
                  <p>Approve: the redacted documents are sent to their destination, as when you approve on the case. Decline: nothing is delivered, the person who submitted the case is told, and it can still be approved on the case.</p>
                  <div className="cf-field">
                    <label htmlFor="cf-ap-reason"><span>Reason</span><span className="cf-field-opt">Needed to decline</span></label>
                    <select id="cf-ap-reason" value={reason} onChange={(e) => setReason(e.target.value)}>
                      <option value="">Choose a reason</option>
                      {DECLINE_REASONS.map(([value, label]) => <option key={value} value={value}>{label}</option>)}
                    </select>
                  </div>
                  {problem && <Notice tone="danger">{problem}</Notice>}
                  <div className="cf-ad-conn-actions">
                    <button type="button" className="cf-btn" data-variant={declineFirst ? 'ghost' : 'solid'} disabled={busy} onClick={() => void decide('APPROVE')}>
                      {busy && <Spinner label="Sending" />}Approve delivery
                    </button>
                    <button type="button" className="cf-btn" data-variant={declineFirst ? 'solid' : 'ghost'} disabled={busy || !reason} onClick={() => void decide('DECLINE')}>Decline delivery</button>
                  </div>
                </section>
              ) : (
                <>
                  <Notice tone={answer.state === 'REFUSED' ? 'danger' : 'neutral'}>{answer.message}</Notice>
                  <p>{answer.caseId ? <Link to={`/case/${answer.caseId}`}>Open the case</Link> : <Link to="/">Go to Cases</Link>}</p>
                </>
              )}
    </div>
  );
}
