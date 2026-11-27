import { useState } from 'react';
import useSWR from 'swr';
import { apiClient } from '../../api/client';
import type { AutomationPeriod } from '../../types';
import { Spinner } from '../atoms';
import { LoadErrorCard, checked } from '../failure';
import { AUTOMATION_PERIODS, automationHeadline, foundByLine, isAutomationReport } from './automationRules';

/** Task 0105: Reports, Automation. Counts only, of this entity. */
export default function AutomationCard() {
  const [period, setPeriod] = useState<AutomationPeriod>('TODAY');
  const request = `GET /operations/reports/automation?period=${period}`;
  const { data, error, isLoading, mutate } = useSWR(['automation', period],
    () => checked(request, apiClient.getAutomationReport(period), isAutomationReport), { revalidateOnFocus: false });
  return (
    <section className="cf-rp-section" aria-label="Automation">
      <header className="cf-rp-section-head">
        <h2>Automation</h2>
        <div className="cf-segment" role="group" aria-label="Period">
          {AUTOMATION_PERIODS.map((p) => (
            <button key={p.key} type="button" aria-pressed={period === p.key} onClick={() => setPeriod(p.key)}>{p.label}</button>
          ))}
        </div>
      </header>
      {error && !data && <LoadErrorCard title="Automation couldn't be loaded" error={error} request={request} onRetry={() => void mutate()} />}
      {isLoading && !data && <Spinner label="Loading automation" />}
      {data && (
        <>
          <p className="cf-rp-stat-value" data-testid="automation-headline">{automationHeadline(data)}</p>
          <dl className="cf-rp-auto-counts">
            <div className="cf-rp-stat"><dt>Automatic</dt><dd><span className="cf-rp-stat-value">{data.automatic}</span></dd></div>
            <div className="cf-rp-stat"><dt>Placed by a person</dt><dd><span className="cf-rp-stat-value">{data.placedByPerson}</span></dd></div>
            <div className="cf-rp-stat"><dt>Drawn by a person</dt><dd><span className="cf-rp-stat-value">{data.drawnByPerson}</span></dd></div>
          </dl>
          <p className="cf-rp-foot">{foundByLine(data)}</p>
        </>
      )}
    </section>
  );
}
