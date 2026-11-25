import { useState } from 'react';
import useSWR from 'swr';
import { apiClient } from '../../api/client';
import type { EnterpriseIdentityConnection, EnterpriseIdentityOverview, SignInChangeAnswer, SignInTestAnswer } from '../../types';
import { Notice, Spinner } from '../atoms';
import { errorMessage } from '../data';
import { dayAndTime } from '../exportRules';
import { useToast } from '../toast';
import { useUnsavedWork } from '../unsavedWork';
import { HelpTip } from './HelpTip';
import { ConfirmDialog, TextField, when } from './shared';
import { changedByWords, draftProblem, draftStatus, type SignInDraft } from './signInRules';
import { LvText } from '../help/LvCodeLink';

/**
 * Admin › Sign-in (IT Admin). The connection people sign in through, with the read-only test (task 0054), and since task
 * 0058 a change of it: a draft, its test sign-in, switching to it and going back. The connection in use keeps working the
 * whole time. The server decides everything, records every step, and never sends a secret; a pasted test token is sent
 * once, for the check, and is gone from the screen afterwards.
 */
export default function IdentityArea() {
  const { data, mutate } = useSWR<EnterpriseIdentityOverview>('enterprise-identity', () => apiClient.getEnterpriseIdentityOverview(), { revalidateOnFocus: false });
  const active = data?.active ?? null;
  const [testing, setTesting] = useState(false);
  const [answer, setAnswer] = useState<SignInTestAnswer | null>(null);
  const [failed, setFailed] = useState<unknown>(null);

  const test = async () => {
    setTesting(true); setAnswer(null); setFailed(null);
    try { setAnswer(await apiClient.testSignInConnection()); }
    catch (error) { setFailed(error); }
    finally { setTesting(false); }
  };

  return (
    <div className="cf-ad-stack">
      <section className="cf-ad-card" aria-label="Sign-in in use">
        <header className="cf-ad-card-head">
          <div>
            <div className="cf-ad-title"><h2>Sign-in in use</h2><HelpTip topic="signIn" /></div>
            <p>The login system people sign in through, for every entity of your organisation.</p>
          </div>
        </header>
        {active && (
          <dl className="cf-facts" aria-label="Login system">
            {active.displayName && <div><dt>Login system</dt><dd>{active.displayName}</dd></div>}
            {active.issuerUri && <div><dt>Address</dt><dd className="cf-mono">{active.issuerUri}</dd></div>}
          </dl>
        )}
        {data && !active && <p className="cf-muted">No sign-in connection is stored. It is stored when LexaVault is set up.</p>}
        {active && (
          <div className="cf-signin-test">
            <button type="button" className="cf-btn" data-size="sm" onClick={() => void test()} disabled={testing}>{testing && <Spinner label="Testing" />}Test sign-in</button>
            <div role="status" aria-live="polite">
              {answer && answer.passed && <p><strong>The login system answers.</strong> {answer.message}</p>}
              {answer && !answer.passed && <p><strong>Sign-in test failed:</strong> <span className="cf-mono"><LvText>{answer.code}</LvText></span> {answer.message}</p>}
              {answer && !answer.passed && answer.whatToDo && <p><strong>What to do:</strong> <LvText>{answer.whatToDo}</LvText></p>}
              {answer?.testedAt && <p className="cf-muted">Tested {dayAndTime(answer.testedAt)}.</p>}
              {failed !== null && <p><strong>The test could not be run.</strong> <LvText>{errorMessage(failed)}</LvText></p>}
            </div>
          </div>
        )}
      </section>
      {data && active && <ChangeSignIn key={`${data.draft?.connectionId ?? 'none'} ${data.draft?.configurationVersion ?? 0}`} data={data} active={active} reload={async () => { await mutate(); }} />}
      {data && (data.history?.length ?? 0) > 0 && (
        <details className="cf-disclosure">
          <summary>Changes so far</summary>
          <div className="cf-ad-table-wrap">
            <table className="cf-ad-table cf-ad-table-left">
              <caption className="cf-sr">Changes of the sign-in connections, newest first</caption>
              <thead><tr><th scope="col">When</th><th scope="col">Address</th><th scope="col">Status</th><th scope="col">By</th></tr></thead>
              <tbody>
                {(data.history ?? []).map((h, i) => (
                  <tr key={`${h.changedAt} ${i}`}>
                    <th scope="row">{when(h.changedAt)}</th><td className="cf-mono">{h.issuerUri}</td><td>{draftStatus(h.status)}</td><td>{changedByWords(h)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </details>
      )}
    </div>
  );
}

const valuesOf = (c: EnterpriseIdentityConnection): SignInDraft => ({ providerId: c.providerId, issuerUri: c.issuerUri, browserClientId: c.browserClientId ?? '', apiAudience: c.apiAudience });

/** Remounted for every version of the draft, so the fields always start from what is stored. */
function ChangeSignIn({ data, active, reload }: { data: EnterpriseIdentityOverview; active: EnterpriseIdentityConnection; reload: () => Promise<void> }) {
  const toast = useToast();
  const draft = data.draft ?? null;
  const previous = data.previous ?? null;
  const [values, setValues] = useState<SignInDraft>(() => valuesOf(draft ?? active));
  const [token, setToken] = useState('');
  const [busy, setBusy] = useState<string | null>(null);
  const [answer, setAnswer] = useState<SignInChangeAnswer | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const [confirm, setConfirm] = useState<'switch' | 'back' | 'discard' | null>(null);
  const dirty = !!draft && JSON.stringify(values) !== JSON.stringify(valuesOf(draft));
  useUnsavedWork(dirty || busy !== null);

  async function run(step: string, call: () => Promise<SignInChangeAnswer>, done: string) {
    setBusy(step); setAnswer(null); setProblem(null);
    try {
      const a = await call();
      setConfirm(null);
      if (a.done) { toast(done); await reload(); }
      else { setAnswer(a); if (a.code === 'IDN_VERSION_CONFLICT') await reload(); }
    } catch (e) {
      setProblem(errorMessage(e, 'The step could not be done.'));
      setConfirm(null);
    } finally {
      setBusy(null);
    }
  }

  const set = (key: keyof SignInDraft) => (v: string) => setValues((old) => ({ ...old, [key]: v }));
  const version = draft?.configurationVersion ?? 0;
  const ready = draft?.status === 'VALIDATED' && !dirty;

  return (
    <section className="cf-ad-card" aria-label="Change the sign-in">
      <header className="cf-ad-card-head">
        <div>
          <div className="cf-ad-title"><h2>Change the sign-in</h2><HelpTip topic="signInDraft" /></div>
          <p>Prepare a change as a draft and test it. People keep signing in as now until you switch.</p>
        </div>
        {draft && <span className="cf-ad-tag">{draftStatus(draft.status)}</span>}
      </header>

      {!draft && (
        <div className="cf-ad-conn-actions">
          <button type="button" className="cf-btn" data-size="sm" disabled={busy !== null}
            onClick={() => void run('start', () => apiClient.startSignInDraft(active.configurationVersion), 'A draft is ready.')}>
            {busy === 'start' && <Spinner label="Starting" />}Prepare a change
          </button>
          {previous && <button type="button" className="cf-btn" data-variant="ghost" data-size="sm" disabled={busy !== null} onClick={() => setConfirm('back')}>Go back to the previous sign-in</button>}
        </div>
      )}

      {draft && (
        <>
          <div className="cf-ad-form">
            <TextField label="Provider ID" required mono value={values.providerId} onChange={set('providerId')} maxLength={100} />
            <TextField label="Address (issuer)" required mono value={values.issuerUri} onChange={set('issuerUri')} maxLength={1000} hint="Your login system’s https address, exactly as it names itself." />
            <TextField label="Web client ID" mono value={values.browserClientId} onChange={set('browserClientId')} maxLength={300} />
            <TextField label="API audience" required mono value={values.apiAudience} onChange={set('apiAudience')} maxLength={300} />
          </div>
          <div className="cf-ad-conn-actions">
            <button type="button" className="cf-btn" data-size="sm" disabled={!dirty || busy !== null || draftProblem(values) !== null}
              onClick={() => void run('save', () => apiClient.saveSignInDraft(version, values), 'The draft is saved. Test it before you switch.')}>
              {busy === 'save' && <Spinner label="Saving" />}Save draft
            </button>
            <button type="button" className="cf-btn" data-variant="ghost" data-size="sm" disabled={busy !== null} onClick={() => setConfirm('discard')}>Discard draft</button>
          </div>
          {dirty && draftProblem(values) && <p className="cf-muted">{draftProblem(values)}</p>}

          <div className="cf-ad-form">
            <TextField label="Test token" type="password" mono value={token} onChange={setToken} maxLength={32768} disabled={dirty}
              hint="A token from your login system, to check its audience and groups too. It is checked once and never kept." />
          </div>
          <div className="cf-ad-conn-actions">
            <button type="button" className="cf-btn" data-size="sm" disabled={dirty || busy !== null}
              onClick={() => { const t = token; setToken(''); void run('test', () => apiClient.testSignInDraft(version, t || null), 'The test sign-in passed.'); }}>
              {busy === 'test' && <Spinner label="Testing" />}Test the draft
            </button>
            <button type="button" className="cf-btn" data-variant="solid" data-size="sm" disabled={!ready || !draft.matchesSettings || busy !== null} onClick={() => setConfirm('switch')}>Switch to it</button>
          </div>
          {!draft.matchesSettings && (
            <Notice>This draft names another login system, provider ID or audience than this server signs people in with, so it can be tested here but not switched to: everybody would be refused. Change the server’s sign-in settings and run setup with these values at the same restart.</Notice>
          )}
        </>
      )}

      <div role="status" aria-live="polite">
        {answer && <p><strong>Not done:</strong> <span className="cf-mono"><LvText>{answer.code}</LvText></span> {answer.message}{answer.reason ? ` (${answer.reason})` : ''}</p>}
        {answer?.whatToDo && <p><strong>What to do:</strong> <LvText>{answer.whatToDo}</LvText></p>}
        {problem && <p><strong>Not done.</strong> <LvText>{problem}</LvText></p>}
      </div>

      {confirm === 'switch' && draft && (
        <ConfirmDialog open title="Switch to the new sign-in?" facts={[['Now', active.issuerUri], ['New', draft.issuerUri], ['Web client ID', draft.browserClientId ?? 'None']]}
          consequence="Everybody signs in through it from now on. You can go back to the one before." confirmLabel="Switch" busy={busy !== null}
          onConfirm={() => void run('switch', () => apiClient.activateSignInDraft(version), 'The new sign-in is in use.')} onCancel={() => setConfirm(null)} />
      )}
      {confirm === 'back' && previous && (
        <ConfirmDialog open title="Go back to the previous sign-in?" facts={[['Now', active.issuerUri], ['Previous', previous.issuerUri], ['Web client ID', previous.browserClientId ?? 'None']]}
          consequence="Everybody signs in through the previous one again." confirmLabel="Go back" busy={busy !== null}
          onConfirm={() => void run('back', () => apiClient.rollBackSignIn(active.configurationVersion), 'The previous sign-in is in use again.')} onCancel={() => setConfirm(null)} />
      )}
      {confirm === 'discard' && (
        <ConfirmDialog open title="Discard the draft?" facts={[['Address', draft?.issuerUri ?? '']]} consequence="The sign-in in use does not change." confirmLabel="Discard" danger busy={busy !== null}
          onConfirm={() => void run('discard', () => apiClient.discardSignInDraft(version), 'The draft is discarded.')} onCancel={() => setConfirm(null)} />
      )}
    </section>
  );
}
