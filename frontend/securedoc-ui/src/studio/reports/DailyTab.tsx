import { useId, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import type { DailyReport } from '../../types';
import { CAP } from '../data';
import { lvEntry } from '../lvCodes';
import { durationText } from '../admin/platformRules';
import { sensitivityName } from '../words';
import { CLOSING_LINE, NOT_BY_LEVEL, WHERE_WORDS, cases, count, documents, inPeriod, pages, quietText, reasonLink, usualText, whenText, type Period, type Preset } from './dailyRules';

/** Task 0081: Reports › Daily intake and delivery. A count links only where the reader may open what it counts. */
export default function DailyTab({ r, preset, period, caps, zone }: { r: DailyReport; preset: Preset; period: Period; caps: string[]; zone: string }) {
  const mayOpenCases = caps.includes(CAP.desk);
  const mayResend = mayOpenCases && caps.includes(CAP.exportResend);
  const maySeeExport = caps.includes(CAP.technicalView);
  const { received, exported, notExported: open, attention, handled } = r;
  const word = (w: string) => inPeriod(w, preset);
  const urgent = (n: number) => (n > 0 ? `${n.toLocaleString()} Urgent` : null);
  const total = attention.refused + attention.stopped + attention.exportFailed;

  return (
    <>
      {r.quiet.map((q) => (
        <p key={q.sourceKey} className="cf-dr-quiet" role="note">
          <span>{quietText(q, zone, r.generatedAt)}</span>
          {caps.includes(CAP.integrations) && <Link to="/admin/integrations">Open the connection</Link>}
        </p>
      ))}

      <div className="cf-dr-tiles">
        <Tile label={word('Received')} value={cases(received.cases)}
          lines={[`${documents(received.documents)} · ${pages(received.pages)}`, urgent(received.urgent), usualText(received.usual, period.fromDay)]} />
        <Tile label={word('Exported')} value={cases(exported.cases)}
          lines={[`${documents(exported.documents)} · ${pages(exported.pages)}`, exported.splitDocuments > 0 ? `plus ${count(exported.splitDocuments, 'split document', 'split documents')}` : null,
            urgent(exported.urgent), usualText(exported.usual, period.fromDay)]} />
        <Tile label="Not exported yet" to={mayOpenCases && open.cases > 0 ? '/?stage=exporting' : null} value={cases(open.cases)}
          lines={[open.cases === 0 ? 'Nothing is waiting' : `Oldest waiting ${durationText(open.oldestWaitingSeconds ?? 0)}`, urgent(open.urgent)]}>
          {open.cases > 0 && (
            <dl className="cf-dr-split">
              <Part label="Ready for export" n={open.ready} />
              <Part label="Waiting to retry" n={open.waitingToRetry} />
              <Part label="Destination paused" n={open.destinationPaused} to={maySeeExport ? '/support/export' : null} />
              <Part label="Waiting for you" n={open.waitingForYou} to={mayResend ? '/waiting' : null} />
            </dl>
          )}
        </Tile>
        <Tile label="Needs attention" tone={total > 0 ? 'danger' : undefined} href={total > 0 ? '#cf-dr-reasons' : null} value={cases(total)}
          lines={[r.refusalsCounted ? null : NOT_BY_LEVEL]}>
          <dl className="cf-dr-split">
            {r.refusalsCounted && <Part label={word('Refused')} n={attention.refused} />}
            <Part label="Stopped now" n={attention.stopped} />
            <Part label="Export failed now" n={attention.exportFailed} />
          </dl>
        </Tile>
      </div>

      <Block id="cf-dr-reasons" title="Needs attention, by reason" note="Look a code up in the error code guide">
        {r.reasons.length === 0 ? <Nothing>Nothing needs attention.</Nothing> : (
          <Table label="Needs attention, by reason" heads={['Reason', 'Where it happened', 'Cases']} left={2}>
            {r.reasons.map((x) => {
              const to = reasonLink(x, mayOpenCases);
              return (
                <tr key={`${x.where} ${x.code}`}>
                  <th scope="row"><code className="cf-dr-code">{x.code}</code> {lvEntry(x.code).title}</th>
                  <td>{WHERE_WORDS[x.where] ?? x.where}</td>
                  <td>{to ? <Link to={to} aria-label={`${cases(x.cases)} with ${x.code}`}>{x.cases.toLocaleString()}</Link> : x.cases.toLocaleString()}</td>
                </tr>
              );
            })}
          </Table>
        )}
      </Block>

      <Block title={`${word('Exported')}, by destination`} note="Everything exported in the period, whenever it was received. Failed and waiting to retry are as of now.">
        {r.destinations.length === 0 ? <Nothing>No destination is set up.</Nothing> : (
          <Table label="Exported, by destination" heads={['Destination', 'Exported', 'Documents', 'Pages', 'Failed', 'Waiting to retry']}>
            {r.destinations.map((d) => (
              <tr key={`${d.key} ${d.split}`}>
                <th scope="row">{d.name}{d.paused && <span className="cf-dr-tag">Paused</span>}</th>
                <td>{d.split ? count(d.exported, 'split document', 'split documents') : cases(d.exported)}</td>
                <td>{d.documents.toLocaleString()}</td>
                <td>{d.pages.toLocaleString()}</td>
                <td data-tone={d.failed > 0 ? 'danger' : undefined}>{d.failed.toLocaleString()}</td>
                <td>{d.waitingToRetry.toLocaleString()}</td>
              </tr>
            ))}
          </Table>
        )}
      </Block>

      <Block title={`${word('Received')}, by source system`} note={r.refusalsCounted ? 'Exported, in progress and stopped: of the cases received in the period' : NOT_BY_LEVEL}>
        {r.sources.length === 0 ? <Nothing>No source system is set up.</Nothing> : (
          <Table label="Received, by source system" heads={['Source system', 'Last received', 'Received', 'Duplicates', 'Refused', 'Exported', 'In progress', 'Stopped']}>
            {r.sources.map((s) => (
              <tr key={s.key} data-quiet={s.quiet || undefined}>
                <th scope="row">{s.name}{s.quiet && <span className="cf-dr-tag">Quiet</span>}</th>
                <td>{whenText(s.lastReceivedAt, zone, r.generatedAt)}</td>
                <td>{s.received.toLocaleString()}</td>
                <td>{r.refusalsCounted ? s.duplicates.toLocaleString() : '—'}</td>
                <td>{r.refusalsCounted ? s.refused.toLocaleString() : '—'}</td>
                <td>{s.exported.toLocaleString()}</td>
                <td>{s.inProgress.toLocaleString()}</td>
                <td data-tone={s.stopped > 0 ? 'danger' : undefined}>{s.stopped.toLocaleString()}</td>
              </tr>
            ))}
          </Table>
        )}
        {r.waitingForTrigger > 0 && <p className="cf-rp-foot">{count(r.waitingForTrigger, 'folder drop waits', 'folder drops wait')} for the trigger file.</p>}
      </Block>

      <div className="cf-rp-pair">
        <Block title={preset === 'TODAY' ? 'How today’s cases were handled' : 'How the cases were handled'} note="Cases received in the period">
          <Table label="How the cases were handled" heads={['Result', 'Cases']}>
            {([['Finished with no person', handled.noPerson], ['Finished after review', handled.afterReview], ['Finished by hand after a stop', handled.byHand],
              ['Still in progress', handled.inProgress], ['Stopped', handled.stopped], ...(handled.rejected > 0 ? [['Rejected', handled.rejected] as const] : [])] as const)
              .map(([label, n]) => <tr key={label}><th scope="row">{label}</th><td>{n.toLocaleString()}</td></tr>)}
          </Table>
        </Block>
        <Block title="By sensitivity level" note="Documents, as the source system set it">
          <Table label="By sensitivity level" heads={['Level', 'Received', 'Exported']}>
            {r.levels.map((l) => <tr key={l.level}><th scope="row">{sensitivityName(l.level)}</th><td>{l.received.toLocaleString()}</td><td>{l.exported.toLocaleString()}</td></tr>)}
          </Table>
          <p className="cf-rp-foot">Nothing sent counts as Standard.</p>
        </Block>
      </div>

      <p className="cf-rp-foot cf-dr-closing">{CLOSING_LINE}</p>
    </>
  );
}

function Tile({ label, value, lines, tone, to, href, children }: { label: string; value: string; lines: (string | null)[]; tone?: 'danger'; to?: string | null; href?: string | null; children?: ReactNode }) {
  const id = useId();
  return (
    <section className="cf-dr-tile" data-tone={tone} aria-labelledby={id}>
      <h2 id={id} className="cf-dr-label">{label}</h2>
      <p className="cf-dr-value">{to ? <Link to={to}>{value}</Link> : href ? <a href={href}>{value}</a> : value}</p>
      {lines.filter(Boolean).map((l) => <p key={l} className="cf-dr-line">{l}</p>)}
      {children}
    </section>
  );
}

function Part({ label, n, to }: { label: string; n: number; to?: string | null }) {
  return <div><dt>{label}</dt><dd>{to && n > 0 ? <Link to={to} aria-label={`${label}: ${n}`}>{n.toLocaleString()}</Link> : n.toLocaleString()}</dd></div>;
}

function Block({ id, title, note, children }: { id?: string; title: string; note?: string; children: ReactNode }) {
  return (
    <section id={id} className="cf-rp-section" aria-label={title}>
      <header className="cf-rp-section-head"><h2>{title}</h2>{note && <p>{note}</p>}</header>
      {children}
    </section>
  );
}

/** `left`: how many columns from the left are words. A wide table scrolls inside its own box. */
function Table({ label, heads, left = 1, children }: { label: string; heads: string[]; left?: number; children: ReactNode }) {
  return (
    <div className="cf-rp-table-wrap" tabIndex={0} role="group" aria-label={label}>
      <table className="cf-rp-table" data-left={left}>
        <thead><tr>{heads.map((h) => <th key={h} scope="col">{h}</th>)}</tr></thead>
        <tbody>{children}</tbody>
      </table>
    </div>
  );
}

const Nothing = ({ children }: { children: ReactNode }) => <p className="cf-rp-scope">{children}</p>;
