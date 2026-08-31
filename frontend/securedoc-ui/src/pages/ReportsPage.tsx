import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { apiClient } from '../api/client';
import type { ApiError, OperationalReports } from '../types';

function Metric({ label, value, note }: { label: string; value: number; note?: string }) {
  return (
    <div className="report-metric">
      <span>{label}</span>
      <strong>{value}</strong>
      {note && <small>{note}</small>}
    </div>
  );
}

export default function ReportsPage() {
  const [report, setReport] = useState<OperationalReports | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = async () => {
    setLoading(true); setError(null);
    try { setReport(await apiClient.getOperationalReports()); }
    catch (err) { const apiErr = err as ApiError; setError(apiErr.message || 'Failed to load reports'); }
    finally { setLoading(false); }
  };

  useEffect(() => { load(); }, []);

  return (
    <div className="reports-page">
      <header className="dashboard-header">
        <div>
          <p className="eyebrow">Operational visibility</p>
          <h1>Reports</h1>
          <p className="dashboard-subtitle">Compact V1 reporting backed by structured intake, processing, export, and retention facts.</p>
        </div>
        <div className="dashboard-header-actions">
          <Link to="/" className="button-link secondary">Back to Dashboard</Link>
          <button className="toolbar-button" onClick={load} disabled={loading}>{loading ? 'Refreshing…' : 'Refresh'}</button>
        </div>
      </header>

      {error && <div className="error-msg">{error}</div>}
      {!report && loading ? <div className="card">Loading reports…</div> : report && (
        <>
          <div className="report-summary-row">
            <div><span>Generated</span><strong>{new Date(report.generatedAt).toLocaleString()}</strong></div>
            <div><span>Total intake batches</span><strong>{report.intake.totalBatches}</strong></div>
            <div><span>Completed / exported</span><strong>{report.processing.completed}</strong></div>
            <div><span>Needs attention</span><strong>{report.processing.needsReview + report.processing.needsApproval + report.processing.technicalAttention}</strong></div>
          </div>

          <div className="report-grid">
            <section className="report-card">
              <div className="report-card-heading"><div><p className="eyebrow">Intake Summary</p><h2>How work arrived</h2></div></div>
              <div className="report-metrics-grid">
                <Metric label="Manual upload" value={report.intake.manual} />
                <Metric label="API" value={report.intake.api} />
                <Metric label="Watched folder" value={report.intake.watchedFolder} />
                <Metric label="Waiting for trigger" value={report.intake.waitingForTrigger} note="Observed folders not yet submitted" />
                <Metric label="Duplicate observations" value={report.intake.duplicateObservations} />
                <Metric label="Rejected observations" value={report.intake.rejectedObservations} />
              </div>
            </section>

            <section className="report-card">
              <div className="report-card-heading"><div><p className="eyebrow">Exceptions & Rejections</p><h2>Items that need attention</h2></div></div>
              <div className="report-metrics-grid">
                <Metric label="Technical attention" value={report.processing.technicalAttention} />
                <Metric label="Rejected batches" value={report.processing.rejected} />
                <Metric label="Rejected folder observations" value={report.intake.rejectedObservations} />
                <Metric label="Waiting for trigger" value={report.intake.waitingForTrigger} />
                <Metric label="Needs review" value={report.processing.needsReview} />
                <Metric label="Needs approval" value={report.processing.needsApproval} />
              </div>
            </section>

            <section className="report-card">
              <div className="report-card-heading"><div><p className="eyebrow">Processing Outcomes</p><h2>Current operational stage</h2></div></div>
              <div className="report-metrics-grid">
                <Metric label="Processing" value={report.processing.processing} />
                <Metric label="Needs review" value={report.processing.needsReview} />
                <Metric label="Needs approval" value={report.processing.needsApproval} />
                <Metric label="Completed" value={report.processing.completed} />
                <Metric label="Technical attention" value={report.processing.technicalAttention} />
                <Metric label="Rejected" value={report.processing.rejected} />
              </div>
            </section>

            <section className="report-card">
              <div className="report-card-heading"><div><p className="eyebrow">Export Status</p><h2>Customer delivery</h2></div></div>
              <div className="report-metrics-grid three">
                <Metric label="Exported packages" value={report.export.exportedPackages} />
                <Metric label="Successful export cycles" value={report.export.successfulExportCycles} />
                <Metric label="Awaiting final approval" value={report.export.pendingFinalApproval} />
              </div>
            </section>

            <section className="report-card">
              <div className="report-card-heading"><div><p className="eyebrow">Aging & Retention</p><h2>Next 24 hours</h2></div></div>
              <div className="report-metrics-grid">
                <Metric label="Originals due" value={report.retention.originalsDueWithin24Hours} />
                <Metric label="Redacted cache due" value={report.retention.redactedCopiesDueWithin24Hours} />
                <Metric label="Technical holds due" value={report.retention.technicalHoldsDueWithin24Hours} />
                <Metric label="Purged packages" value={report.retention.purgedPackages} />
              </div>
              <p className="report-note">These are operational counts, not a long-term analytics warehouse. External BI can query the same structured records without parsing logs.</p>
            </section>
          </div>
        </>
      )}
    </div>
  );
}
