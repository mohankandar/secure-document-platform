import { useState, type FormEvent } from 'react';
import useSWR from 'swr';
import { apiClient } from '../../api/client';
import type { ProcessingCapacity } from '../../types';
import { errorMessage } from '../data';
import { Notice, Spinner } from '../atoms';
import { useToast } from '../toast';
import { Loading, TextField } from './shared';
import { aiServerText, capacityNote, maximumChanges, maximumError, stageName } from './platformRules';
import { HelpTip } from './HelpTip';

/** Admin › Platform operations › Workers (task 0067). Platform Operations only. */
export default function CapacitySection() {
  const toast = useToast();
  const { data, error, mutate, isValidating } = useSWR<ProcessingCapacity>('platform-capacity', () => apiClient.getProcessingCapacity(), { refreshInterval: 10_000 });
  const [edits, setEdits] = useState<Record<string, string> | null>(null);
  const [formError, setFormError] = useState('');
  const [saving, setSaving] = useState(false);

  if (!data) return <Loading error={error} retry={() => mutate()} label="Workers" />;
  const saved: Record<string, number | null> = Object.fromEntries([...data.stages.map((s) => [s.stage, s.maximum ?? null]), ['AI', data.aiMaximum ?? null]]);
  const note = capacityNote(data);

  function startEditing() {
    setFormError('');
    setEdits(Object.fromEntries(Object.entries(saved).map(([k, v]) => [k, v === null ? '' : String(v)])));
  }

  async function save(e: FormEvent) {
    e.preventDefault();
    if (!edits) return;
    if (Object.values(edits).some(maximumError)) { setFormError('A maximum is a whole number from 1 to 64, or empty.'); return; }
    const changes = maximumChanges(saved, edits);
    if (Object.keys(changes).length === 0) { setEdits(null); return; }
    setSaving(true);
    try {
      await mutate(await apiClient.changeProcessingMaximums(changes), { revalidate: false });
      setEdits(null);
      toast('Maximums saved. Every server follows within a few seconds.');
    } catch (err) {
      setFormError(errorMessage(err, "The maximums couldn't be saved. Nothing was changed."));
    } finally {
      setSaving(false);
    }
  }

  return (
    <section className="cf-ad-card" aria-labelledby="cf-cp-head">
      <header className="cf-ad-card-head">
        <div>
          <div className="cf-ad-title"><h2 id="cf-cp-head">Workers on this server</h2><HelpTip topic="capacity" /></div>
          <p>LexaVault adds workers while documents wait and takes them away again. The highest is measured when it starts, unless you set a maximum.</p>
        </div>
        <button type="button" className="cf-btn" data-variant="ghost" data-size="sm" disabled={isValidating} onClick={() => mutate()}>Refresh</button>
      </header>
      {note && <Notice tone={note.tone}>{note.text}</Notice>}

      <div className="cf-ad-table-wrap">
        <table className="cf-ad-table">
          <thead><tr><th scope="col">Step</th><th scope="col">Now</th><th scope="col">Lowest</th><th scope="col">Highest</th><th scope="col">Waiting</th><th scope="col">Your maximum</th></tr></thead>
          <tbody>
            {data.stages.map((s) => (
              <tr key={s.stage}>
                <th scope="row">{stageName(s.stage)}</th>
                <td>{s.current}</td><td>{s.lowest}</td><td>{s.highest}</td><td>{s.waiting}</td>
                <td>{s.maximum ?? 'Not set'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className="cf-ad-block">
        <h3 className="cf-ad-block-title">AI servers</h3>
        {data.aiServers.length === 0
          ? <p className="cf-ad-hint">{data.staged ? 'No AI server has been used since LexaVault started.' : 'Learned only while documents are processed in steps.'}</p>
          : <ul className="cf-ad-list">{data.aiServers.map((a) => <li key={a.number}>{aiServerText(a)}</li>)}</ul>}
      </div>

      {edits ? (
        <form className="cf-ad-form cf-ad-block" onSubmit={save} noValidate aria-labelledby="cf-cp-edit">
          <h3 id="cf-cp-edit" className="cf-ad-block-title">Maximums for every server</h3>
          <p className="cf-ad-hint">Empty means LexaVault decides.</p>
          <div className="cf-ad-grid">
            {data.stages.map((s) => (
              <TextField key={s.stage} label={stageName(s.stage)} value={edits[s.stage]} onChange={(v) => setEdits({ ...edits, [s.stage]: v })} maxLength={2}
                invalid={maximumError(edits[s.stage])} hint={`Measured on this server: ${s.measured}`} />
            ))}
            <TextField label="Requests at once per AI server" value={edits.AI} onChange={(v) => setEdits({ ...edits, AI: v })} maxLength={2} invalid={maximumError(edits.AI)} />
          </div>
          {formError && <Notice tone="danger">{formError}</Notice>}
          <div className="cf-ad-card-foot">
            <button type="button" className="cf-btn" data-variant="ghost" data-size="sm" disabled={saving} onClick={() => setEdits(null)}>Cancel</button>
            <button type="submit" className="cf-btn" data-variant="solid" data-size="sm" disabled={saving}>{saving && <Spinner label="Saving" />}Save maximums</button>
          </div>
        </form>
      ) : (
        <div className="cf-ad-card-foot cf-ad-foot-left">
          <button type="button" className="cf-btn" data-size="sm" onClick={startEditing}>Change maximums</button>
          <span className="cf-ad-hint">AI requests at once: {data.aiMaximum ?? 'no maximum set here'}.</span>
        </div>
      )}
    </section>
  );
}
