import { useState } from 'react';
import useSWR from 'swr';
import { apiClient } from '../../api/client';
import type { NotificationBusinessOverview, NotificationEvent, NotificationEventChange, NotificationItOverview, NotificationRoleCount } from '../../types';
import { CAP, errorMessage } from '../data';
import { checked, isRecord } from '../failure';
import { Notice, Spinner } from '../atoms';
import { useToast } from '../toast';
import { useUnsavedWork } from '../unsavedWork';
import { HelpTip } from './HelpTip';
import { Loading, NumberField, SelectField, Sheet, TextField, Toggle, when } from './shared';
import { FREQUENCY_WORDS, ROLE_WORDS, deliveryState, eventTiming, mailForm, mailRequest, needsSecret, peopleText, roleNames, type MailForm } from './notificationRules';
import { LvText } from '../help/LvCodeLink';

/**
 * Admin › Notifications (task 0059), always for the entity the person is in: the IT Admin sets the e-mail and the technical
 * messages, the Business Admin the business ones. A secret is named here and never typed; the server never sends its name back.
 */
export default function NotificationsArea({ caps }: { caps: string[] }) {
  return (
    <div className="cf-ad-stack">
      {caps.includes(CAP.integrations) && <ItPart />}
      {caps.includes(CAP.policy) && <BusinessPart />}
    </div>
  );
}

function ItPart() {
  const { data, error, mutate } = useSWR<NotificationItOverview>('notifications-it',
    () => checked('GET /admin/notifications/it', apiClient.getNotificationsIt(), (v) => isRecord(v) && Array.isArray(v.events) && Array.isArray(v.people)), { revalidateOnFocus: false });
  if (!data) return <Loading error={error} retry={() => mutate()} label="Notifications" />;
  return (
    <>
      <MailCard key={JSON.stringify(data.mail)} data={data} onSaved={(next) => void mutate(next, { revalidate: false })} reload={() => void mutate()} />
      <PeopleCard people={data.people} />
      <EventsCard id="cf-nt-tech" title="Technical messages" text="Sent to this entity’s IT Admins. A message marked “Always on” can’t be switched off." events={data.events}
        save={async (id, body) => { await mutate(await apiClient.saveNotificationEvent('it', id, body) as NotificationItOverview, { revalidate: false }); }} />
      <RecentCard recent={data.recent} />
    </>
  );
}

function BusinessPart() {
  const { data, error, mutate } = useSWR<NotificationBusinessOverview>('notifications-business',
    () => checked('GET /admin/notifications/business', apiClient.getNotificationsBusiness(), (v) => isRecord(v) && Array.isArray(v.events)), { revalidateOnFocus: false });
  if (!data) return <Loading error={error} retry={() => mutate()} label="Notifications" />;
  return (
    <>
      {!data.mailOn && <Notice>E-mail is not set up for this entity yet, so nothing is sent. Your IT Admin sets it up here.</Notice>}
      <PeopleCard people={data.people} />
      <EventsCard id="cf-nt-business" title="Business messages" text="Work that waits, approvals, the licence and its use, and changes of the redaction policy." events={data.events}
        save={async (id, body) => { await mutate(await apiClient.saveNotificationEvent('business', id, body) as NotificationBusinessOverview, { revalidate: false }); }} />
    </>
  );
}

function MailCard({ data, onSaved, reload }: { data: NotificationItOverview; onSaved: (next: NotificationItOverview) => void; reload: () => void }) {
  const toast = useToast();
  const [form, setForm] = useState<MailForm>(() => mailForm(data.mail));
  const [busy, setBusy] = useState<'save' | 'test' | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const changed = JSON.stringify(form) !== JSON.stringify(mailForm(data.mail));
  useUnsavedWork(changed);
  const set = <K extends keyof MailForm>(key: K) => (value: MailForm[K]) => setForm((f) => ({ ...f, [key]: value }));
  const microsoft = form.mode === 'MICROSOFT_365';

  async function run(what: 'save' | 'test', call: () => Promise<void>) {
    setBusy(what);
    setProblem(null);
    try { await call(); } catch (e) { setProblem(errorMessage(e, what === 'save' ? 'The settings could not be saved.' : 'The test message could not be sent.')); } finally { setBusy(null); }
  }

  return (
    <section className="cf-ad-card" aria-labelledby="cf-nt-mail">
      <header className="cf-ad-card-head">
        <div>
          <div className="cf-ad-title"><h2 id="cf-nt-mail">E-mail</h2><HelpTip topic="notifications" /></div>
          <p>Messages of this entity are sent through its own mail account. They hold no document content, no case number and no redacted value.</p>
        </div>
      </header>
      {!data.appAddressSet && <Notice tone="mark">The address of LexaVault is not set for this installation, so nothing is sent. Whoever runs the server sets <span className="cf-ad-mono">securedoc.notifications.app-base-url</span>.</Notice>}
      {!data.approvalLinksOn && <Notice>Approval links are off for this installation. Delivery is approved on the case.</Notice>}
      <form className="cf-ad-stack" onSubmit={(e) => { e.preventDefault(); void run('save', async () => { onSaved(await apiClient.saveNotificationMail(mailRequest(form))); toast('The e-mail settings are saved.'); }); }}>
        <Toggle label="Send messages by e-mail" checked={form.enabled} onChange={set('enabled')} />
        <SelectField label="Mail account" value={form.mode} onChange={set('mode')} options={[['MICROSOFT_365', 'Microsoft 365 (sign-in without a password)'], ['OWN_SERVER', 'Your own Exchange server or relay']]} />
        {microsoft ? (
          <>
            <TextField label="Directory (tenant) ID" required mono value={form.directoryId} onChange={set('directoryId')} maxLength={253} />
            <TextField label="Application (client) ID" required mono value={form.clientId} onChange={set('clientId')} maxLength={64} />
            <TextField label="Sender mailbox" required value={form.fromAddress} onChange={set('fromAddress')} maxLength={320} hint="The app signs in to send as this mailbox." />
            <SelectField label="Credential" value={form.credentialType} onChange={set('credentialType')} options={[['CERTIFICATE', 'Certificate with its key (recommended)'], ['SECRET', 'Client secret']]} />
          </>
        ) : (
          <>
            <TextField label="Server" required value={form.host} onChange={set('host')} maxLength={253} />
            <SelectField label="Security" value={form.security} onChange={set('security')} options={[['STARTTLS', 'STARTTLS'], ['TLS', 'TLS'], ['NONE', 'None (a relay without a login, in your own network)']]} />
            <SelectField label="Port" value={form.port} onChange={set('port')} options={Array.from(new Set([...data.mailPorts.map(String), form.port])).map((p) => [p, p] as [string, string])} />
            <TextField label="User name" value={form.username} onChange={set('username')} maxLength={320} hint="Leave empty for a relay without a login." />
            <TextField label="Sender address" required value={form.fromAddress} onChange={set('fromAddress')} maxLength={320} />
          </>
        )}
        <TextField label="Sender name" value={form.fromName} onChange={set('fromName')} maxLength={100} />
        {(microsoft || form.username.trim() !== '') && (
          <TextField label="Name of the stored secret" required={needsSecret(form, data.mail)} mono value={form.secretReference} onChange={set('secretReference')} maxLength={256}
            placeholder={`env:${data.secretNamePrefix}MAIL`}
            hint={<>{data.mail?.secretNamed && data.mail.mode === form.mode ? 'One is stored; leave this empty to keep it. ' : ''}Never the secret itself. It starts with <span className="cf-ad-mono">env:</span> or <span className="cf-ad-mono">file:</span>, then <span className="cf-ad-mono cf-nt-break">{data.secretNamePrefix}</span>.</>} />
        )}
        {problem && <Notice tone="danger">{problem}</Notice>}
        <div className="cf-ad-conn-actions">
          <button type="submit" className="cf-btn" data-variant="solid" data-size="sm" disabled={busy !== null || !changed}>{busy === 'save' && <Spinner label="Saving" />}Save</button>
          <button type="button" className="cf-btn" data-variant="ghost" data-size="sm" disabled={busy !== null || changed || !data.mail?.enabled}
            onClick={() => void run('test', async () => { await apiClient.sendNotificationTest(); toast('A test message is on its way to your own address.'); window.setTimeout(reload, 12_000); })}>
            {busy === 'test' && <Spinner label="Sending" />}Send me a test message
          </button>
        </div>
      </form>
    </section>
  );
}

function PeopleCard({ people }: { people: NotificationRoleCount[] }) {
  return (
    <section className="cf-ad-card" aria-labelledby="cf-nt-people">
      <header className="cf-ad-card-head">
        <div>
          <div className="cf-ad-title"><h2 id="cf-nt-people">Who gets messages</h2><HelpTip topic="notificationPeople" /></div>
          <p>People with the role in this entity who signed in lately. Someone new gets messages after their first sign-in.</p>
        </div>
      </header>
      <dl className="cf-nt-people">
        {people.map((p) => <div key={p.role}><dt>{ROLE_WORDS[p.role] ?? p.role}</dt><dd>{peopleText(p.people)}</dd></div>)}
      </dl>
    </section>
  );
}

function EventsCard({ id, title, text, events, save }: { id: string; title: string; text: string; events: NotificationEvent[]; save: (id: string, body: NotificationEventChange) => Promise<void> }) {
  const [open, setOpen] = useState<NotificationEvent | null>(null);
  return (
    <section className="cf-ad-card" aria-labelledby={id}>
      <header className="cf-ad-card-head"><div><h2 id={id}>{title}</h2><p>{text}</p></div></header>
      <div className="cf-ad-table-wrap">
        <table className="cf-ad-table cf-ad-table-left">
          <caption className="cf-sr">{title}</caption>
          <thead><tr><th scope="col">Message</th><th scope="col">To</th><th scope="col">When</th><th scope="col">State</th><th scope="col"><span className="cf-sr">Actions</span></th></tr></thead>
          <tbody>
            {events.map((e) => (
              <tr key={e.id}>
                <th scope="row">{e.name}</th>
                <td>{roleNames(e.roles)}</td>
                <td>{eventTiming(e)}</td>
                <td><span className="cf-ad-tag">{e.alwaysOn ? 'Always on' : e.enabled ? 'On' : 'Off'}</span></td>
                <td><button type="button" className="cf-btn" data-size="sm" data-variant="ghost" aria-label={`Change: ${e.name}`} onClick={() => setOpen(e)}>Change</button></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {open && <EventSheet key={open.id} event={open} save={save} onClose={() => setOpen(null)} />}
    </section>
  );
}

function EventSheet({ event: e, save, onClose }: { event: NotificationEvent; save: (id: string, body: NotificationEventChange) => Promise<void>; onClose: () => void }) {
  const toast = useToast();
  const [enabled, setEnabled] = useState(e.enabled);
  const [frequency, setFrequency] = useState(e.frequency);
  const [dailyAt, setDailyAt] = useState(e.dailyAt ?? '08:00');
  const [alertAfter, setAlertAfter] = useState(e.alertAfterMinutes ?? 0);
  const [repeat, setRepeat] = useState(e.repeatMinutes ?? 0);
  const [thresholds, setThresholds] = useState<Record<string, number>>(() => Object.fromEntries(e.thresholds.map((t) => [t.name, t.value])));
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const lasting = e.alertAfterMinutes !== null && e.repeatMinutes !== null;
  async function run() {
    setBusy(true);
    setProblem(null);
    try {
      await save(e.id, {
        enabled, frequency, ...(frequency === 'ONCE_A_DAY' ? { dailyAt } : {}),
        ...(lasting ? { alertAfterMinutes: alertAfter, repeatMinutes: repeat } : {}), ...(e.thresholds.length ? { thresholds } : {}),
      });
      toast('Saved.');
      onClose();
    } catch (err) { setProblem(errorMessage(err, 'The choice could not be saved.')); } finally { setBusy(false); }
  }
  return (
    <Sheet open title={e.name} onClose={onClose} footer={<>
      <button type="button" className="cf-btn" data-variant="ghost" data-size="sm" disabled={busy} onClick={onClose}>Cancel</button>
      <button type="button" className="cf-btn" data-variant="solid" data-size="sm" disabled={busy} onClick={() => void run()}>{busy && <Spinner label="Saving" />}Save</button>
    </>}>
      {e.alwaysOn ? <p className="cf-muted">This message can’t be switched off, and goes out at least every hour.</p> : <Toggle label="Send this message" checked={enabled} onChange={setEnabled} />}
      {e.frequencies.length > 1 ? (
        <SelectField label="How often" value={frequency} onChange={setFrequency} options={e.frequencies.map((f) => [f, FREQUENCY_WORDS[f] ?? f] as [string, string])} hint="Anything but “Immediately” is one message with everything since the last one." />
      ) : <p className="cf-muted">Sent immediately.</p>}
      {frequency === 'ONCE_A_DAY' && <TextField label="Time of day" required type="time" value={dailyAt} onChange={setDailyAt} />}
      {lasting && <>
        <NumberField label="Tell after" suffix="minutes" value={alertAfter} onChange={setAlertAfter} min={0} max={e.alertAfterMax} hint="How long it has to last first." />
        <NumberField label="Tell again every" suffix="minutes" value={repeat} onChange={setRepeat} min={15} max={1440} hint="While it lasts." />
      </>}
      {e.thresholds.map((t) => <NumberField key={t.name} label={t.label} value={thresholds[t.name]} onChange={(v) => setThresholds((x) => ({ ...x, [t.name]: v }))} min={t.min} max={t.max} />)}
      {problem && <Notice tone="danger">{problem}</Notice>}
    </Sheet>
  );
}

function RecentCard({ recent }: { recent: NotificationItOverview['recent'] }) {
  return (
    <section className="cf-ad-card" aria-labelledby="cf-nt-recent">
      <header className="cf-ad-card-head"><div><h2 id="cf-nt-recent">Last messages</h2><p>The last 10 of this entity, with the reason when one could not be sent.</p></div></header>
      {recent.length === 0 ? <p className="cf-muted">None yet.</p> : (
        <div className="cf-ad-table-wrap">
          <table className="cf-ad-table cf-ad-table-left">
            <caption className="cf-sr">Last messages</caption>
            <thead><tr><th scope="col">Message</th><th scope="col">State</th><th scope="col">When</th></tr></thead>
            <tbody>
              {recent.map((r, i) => (
                <tr key={i}>
                  <th scope="row">{r.event}</th>
                  <td><span className="cf-ad-tag" data-tone={r.state === 'SENT' ? 'ok' : undefined}>{deliveryState(r.state)}</span>{r.reason && <><br /><span className="cf-muted">{r.reason} (<LvText>{r.code}</LvText>)</span></>}</td>
                  <td>{when(r.at)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
