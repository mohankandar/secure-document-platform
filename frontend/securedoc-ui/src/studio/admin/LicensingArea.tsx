import useSWR from 'swr';
import { apiClient } from '../../api/client';
import type { LicensingView } from '../../types';
import { checked, isRecord } from '../failure';
import { Notice } from '../atoms';
import { whatToDo } from '../lvCodes';
import { Loading } from './shared';
import { HelpTip } from './HelpTip';
import { allowanceText, dayText, levelTone, licenceKind, licenceTerm, licenceTitle, monthText } from './licensingRules';

/**
 * Admin › Licensing (task 0057, Business Admin): the licence in force and this month's use of this entity. Read-only: a
 * licence is stored with the licence command by IT.
 */
export default function LicensingArea() {
  const { data, error, mutate, isValidating } = useSWR<LicensingView>('licensing',
    () => checked('GET /licensing', apiClient.getLicensing(), (v) => isRecord(v) && typeof v.intakeAllowed === 'boolean'), { revalidateOnFocus: false });
  if (!data) return <Loading error={error} retry={() => mutate()} label="Licensing" />;
  const l = data.licence;

  return (
    <div className="cf-ad-stack">
      <section className="cf-ad-card" aria-labelledby="cf-li-head">
        <header className="cf-ad-card-head">
          <div>
            <div className="cf-ad-title"><h2 id="cf-li-head">{licenceTitle(data)}</h2><HelpTip topic="licensing" /></div>
            <p>{l ? l.message : 'The licence can’t be read.'}</p>
          </div>
          <button type="button" className="cf-btn" data-variant="ghost" data-size="sm" disabled={isValidating} onClick={() => mutate()}>Refresh</button>
        </header>

        {data.stopped && (
          <Notice tone="danger">
            <strong>{data.stopped.title} · {data.stopped.code}</strong>
            <br />New cases are not taken in. Cases already in LexaVault are finished, and viewing and export keep working.
            <br />What to do: {whatToDo(data.stopped.code)}
          </Notice>
        )}
        {l?.testEnvironment && <Notice>Test environment. This licence is not for production use: every redacted copy is marked “TEST – NOT FOR PRODUCTION USE”, and pages are never billed.</Notice>}
        {l?.clockMessage && <Notice tone="danger">{l.clockMessage}</Notice>}

        {l && (
          <dl className="cf-ad-facts">
            <div><dt>Customer</dt><dd>{l.customer}</dd></div>
            <div><dt>Licence</dt><dd>{l.licenceId}, version {l.version}</dd></div>
            <div><dt>Kind</dt><dd>{licenceKind(l.kind)}</dd></div>
            <div><dt>Started</dt><dd>{dayText(l.startsOn)}</dd></div>
            <div><dt>{l.expiresOn ? 'Valid until' : 'Updates until'}</dt><dd>{licenceTerm(l)}</dd></div>
            {l.waiting && <div><dt>Renewal waiting</dt><dd>{l.waiting.licenceId}, from {dayText(l.waiting.startsOn)}</dd></div>}
          </dl>
        )}
      </section>

      {data.entity && (
        <section className="cf-ad-card" aria-labelledby="cf-li-use">
          <header className="cf-ad-card-head">
            <div>
              <h2 id="cf-li-use">Use in {monthText(data.entity.month)}</h2>
              <p>This entity only. A document’s pages are counted once, when its redacted copy is made. Work never stops at a limit.</p>
            </div>
          </header>
          <dl className="cf-ad-facts">
            {data.entity.allowances.map((a) => {
              const text = allowanceText(a);
              return (
                <div key={a.type}>
                  <dt>{text.name}</dt>
                  <dd>{text.use} <span className="cf-ad-tag" data-tone={levelTone(a.level)}>{text.level}</span></dd>
                </div>
              );
            })}
          </dl>
        </section>
      )}

      <details className="cf-disclosure">
        <summary>Technical details</summary>
        <dl className="cf-ad-facts cf-ad-facts-compact">
          <div><dt>State</dt><dd className="cf-ad-mono">{l?.state ?? 'none'}</dd></div>
          {data.stopped?.platformCode && <div><dt>Reason</dt><dd className="cf-ad-mono">{data.stopped.platformCode}</dd></div>}
          {data.entity && <div><dt>Entity slot</dt><dd className="cf-ad-mono">{data.entity.slotId}</dd></div>}
        </dl>
      </details>
    </div>
  );
}
