import { useState } from 'react';
import useSWR from 'swr';
import { apiClient } from '../../api/client';
import type { EntityChangeAnswer, EntitySlot, EntitySlotsOverview } from '../../types';
import { checked, isRecord } from '../failure';
import { Notice, Spinner } from '../atoms';
import { errorMessage } from '../data';
import { useToast } from '../toast';
import { useUnsavedWork } from '../unsavedWork';
import { ConfirmDialog, Loading, SelectField, Sheet, TextField } from './shared';
import { HelpTip } from './HelpTip';
import { ENTITY_NAME_MAX, entityName, entityStatus, timeZoneNames, timeZoneText, type EntityAction } from './entityRules';

/**
 * Admin › Entities (task 0058, Business Admin): activate, name, suspend and resume the entities of the licence, and their
 * time zones. The server shows and changes only the entities this person administers; every change is recorded there.
 */
export default function EntitiesArea() {
  const { data, error, mutate } = useSWR<EntitySlotsOverview>('entities',
    () => checked('GET /admin/entities', apiClient.getEntities(), (v) => isRecord(v) && Array.isArray(v.entities)), { revalidateOnFocus: false });
  const [open, setOpen] = useState<{ action: EntityAction; slot: EntitySlot } | null>(null);
  if (!data) return <Loading error={error} retry={() => mutate()} label="Entities" />;
  const close = (changed: boolean) => { setOpen(null); if (changed) void mutate(); };
  return (
    <div className="cf-ad-stack">
      <section className="cf-ad-card" aria-labelledby="cf-en-head">
        <header className="cf-ad-card-head">
          <div>
            <div className="cf-ad-title"><h2 id="cf-en-head">Entities</h2><HelpTip topic="entities" /></div>
            <p>Each entity keeps its own cases, rules and people. The licence gives the entities; you activate and name them.</p>
          </div>
        </header>
        {data.entities.length === 0 ? <p className="cf-muted">No entities to show. They are stored when LexaVault is set up with the licence.</p> : (
          <div className="cf-ad-table-wrap">
            <table className="cf-ad-table cf-ad-table-left">
              <caption className="cf-sr">Entities you administer</caption>
              <thead><tr><th scope="col">Entity</th><th scope="col">Status</th><th scope="col">Time zone</th><th scope="col"><span className="cf-sr">Actions</span></th></tr></thead>
              <tbody>
                {data.entities.map((slot) => {
                  const status = entityStatus(slot);
                  const name = entityName(slot);
                  return (
                    <tr key={slot.slotId}>
                      <th scope="row">
                        <span>{name}</span>{slot.current && <> <span className="cf-ad-tag">You are here</span></>}
                        <br /><span className="cf-muted cf-ad-mono">{slot.slotId}</span>
                      </th>
                      <td><span className="cf-ad-tag" data-tone={status.tone}>{status.label}</span></td>
                      <td>{timeZoneText(slot)}</td>
                      <td>
                        <div className="cf-ad-conn-actions">
                          {status.actions.map((a) => (
                            <button key={a.action} type="button" className="cf-btn" data-size="sm" data-variant={a.action === 'activate' ? 'solid' : 'ghost'}
                              aria-label={`${a.label}: ${slot.name ? name : slot.slotId}`} onClick={() => setOpen({ action: a.action, slot })}>{a.label}</button>
                          ))}
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </section>
      {open && <EntityDialog key={`${open.action} ${open.slot.slotId}`} action={open.action} slot={open.slot} onClose={close} />}
    </div>
  );
}

function EntityDialog({ action, slot, onClose }: { action: EntityAction; slot: EntitySlot; onClose: (changed: boolean) => void }) {
  const toast = useToast();
  const name = entityName(slot);
  const [typed, setTyped] = useState(slot.name ?? '');
  const [zone, setZone] = useState(slot.timeZone ?? '');
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const naming = action === 'activate' || action === 'rename';
  useUnsavedWork(busy || (naming && typed !== (slot.name ?? '')) || (action === 'timeZone' && zone !== (slot.timeZone ?? '')));

  async function run(call: () => Promise<EntityChangeAnswer>, done: string) {
    setBusy(true);
    setProblem(null);
    try {
      const answer = await call();
      if (!answer.done) { setProblem([answer.message, answer.whatToDo].filter(Boolean).join(' ')); return; }
      toast(done);
      onClose(true);
    } catch (e) {
      setProblem(errorMessage(e, 'The change could not be made.'));
    } finally {
      setBusy(false);
    }
  }

  if (action === 'suspend' || action === 'resume') {
    const suspend = action === 'suspend';
    return (
      <ConfirmDialog open title={suspend ? `Suspend ${name}?` : `Resume ${name}?`} facts={[['Entity', name], ['Entity slot', slot.slotId]]}
        consequence={suspend
          ? 'Nobody can work in it, and nothing new is taken in from any source, until it is resumed. Cases already taken in are finished and delivered. Nothing is deleted, and it still counts in the licence.'
          : 'People can work in it again, and documents are taken in again.'}
        confirmLabel={suspend ? 'Suspend' : 'Resume'} danger={suspend} busy={busy} error={problem}
        onConfirm={() => void run(() => (suspend ? apiClient.suspendEntity(slot.slotId) : apiClient.resumeEntity(slot.slotId)), suspend ? `${name} is suspended.` : `${name} is active again.`)}
        onCancel={() => onClose(false)} />
    );
  }

  const title = action === 'activate' ? `Activate ${slot.slotId}` : action === 'rename' ? `Rename ${name}` : `Time zone of ${name}`;
  const trimmed = typed.trim();
  const save = () => {
    if (action === 'activate') return run(() => apiClient.activateEntity(slot.slotId, typed), `${trimmed} is active.`);
    if (action === 'rename') return run(() => apiClient.renameEntity(slot.slotId, typed), 'The name is changed. It shows everywhere from the next page load.');
    return run(() => apiClient.setEntityTimeZone(slot.slotId, zone || null), 'The time zone is changed. It shows everywhere from the next page load.');
  };
  return (
    <Sheet open title={title} onClose={() => onClose(false)}
      footer={<>
        <button type="button" className="cf-btn" data-variant="ghost" data-size="sm" disabled={busy} onClick={() => onClose(false)}>Cancel</button>
        <button type="button" className="cf-btn" data-variant="solid" data-size="sm" disabled={busy || (naming && !trimmed)} onClick={() => void save()}>
          {busy && <Spinner label="Saving" />}{action === 'activate' ? 'Activate' : 'Save'}
        </button>
      </>}>
      {naming && (
        <TextField label="Name" required value={typed} onChange={setTyped} maxLength={ENTITY_NAME_MAX}
          hint="People see this name at the top of every screen. Choose one they can’t mistake for another entity." />
      )}
      {action === 'activate' && <p className="cf-muted">An entity is activated once and counts in the licence from then on. It can be renamed or suspended, never removed.</p>}
      {action === 'timeZone' && (
        <>
          <SelectField label="Time zone" value={zone} onChange={setZone}
            options={[['', slot.customerTimeZone ? `The customer’s (${slot.customerTimeZone})` : 'The customer’s (none set)'], ...timeZoneNames(slot.timeZone).map((z) => [z, z] as [string, string])]}
            hint="Used for daily schedules, reports and the times shown for this entity." />
          <p className="cf-muted">When stored copies are deleted does not change: it is counted in hours from a fixed moment, in every time zone.</p>
        </>
      )}
      {problem && <Notice tone="danger">{problem}</Notice>}
    </Sheet>
  );
}
