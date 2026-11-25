import { useState } from 'react';
import useSWR from 'swr';
import { apiClient } from '../../api/client';
import type { PipelineQueue } from '../../types';
import { checked } from '../failure';
import { Loading } from './shared';
import { durationText, isPipelineQueue, queueHeadline, queueHow, stageEta, stageName } from './platformRules';

/** Admin › Platform operations › Overview (task 0078): the queue of each step and its ETA. Numbers only. `own`: the reader's entity, on Reports (0081). */
export default function QueueSection({ own = false }: { own?: boolean }) {
  const { data, error, mutate, isValidating } = useSWR<PipelineQueue>(own ? 'reports-queue' : 'platform-queue', () => (own ? checked('GET /operations/reports/queue', apiClient.getReportQueue(), isPipelineQueue)
    : checked('GET /platform-admin/queue', apiClient.getPipelineQueue(), isPipelineQueue)), { refreshInterval: 10_000, revalidateOnFocus: false });
  const [how, setHow] = useState(false);

  if (!data) return <Loading error={error} retry={() => mutate()} label="Queue" />;
  return (
    <section className="cf-ad-card" aria-labelledby="cf-pq-head">
      <header className="cf-ad-card-head">
        <div>
          <h2 id="cf-pq-head">{data.allEntities ? 'Queue, every entity together' : 'Queue'}</h2>
          <p>{queueHeadline(data)}{own && ' The whole queue as it is now; the filters do not change it.'}</p>
        </div>
        <button type="button" className="cf-btn" data-variant="ghost" data-size="sm" disabled={isValidating} onClick={() => mutate()}>Refresh</button>
      </header>
      {data.staged && (
        <>
          <div className="cf-ad-table-wrap" tabIndex={0} role="group" aria-label="Queue by step">
            <table className="cf-ad-table">
              <thead><tr>{['Step', 'Waiting', 'Running', 'Still to come', 'On hold', 'Workers', 'Oldest waiting', 'Time left'].map((h) => <th key={h} scope="col">{h}</th>)}</tr></thead>
              <tbody>
                {data.stages.map((s) => (
                  <tr key={s.stage}>
                    <th scope="row">{stageName(s.stage)}</th>
                    <td>{s.waiting}</td><td>{s.running}</td><td>{s.toCome}</td><td>{s.held}</td>
                    <td>{s.workers} of {s.highest}</td>
                    <td>{s.oldestWaitingSeconds === null || s.oldestWaitingSeconds === undefined ? '—' : durationText(s.oldestWaitingSeconds)}</td>
                    <td>{stageEta(s)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="cf-ad-card-foot cf-ad-foot-left">
            <button type="button" className="cf-btn" data-variant="ghost" data-size="sm" aria-expanded={how} aria-controls={how ? 'cf-pq-how' : undefined} onClick={() => setHow(!how)}>How is this worked out?</button>
          </div>
          {how && <ul id="cf-pq-how" className="cf-ad-list">{queueHow(data).map((line) => <li key={line}>{line}</li>)}</ul>}
        </>
      )}
    </section>
  );
}
