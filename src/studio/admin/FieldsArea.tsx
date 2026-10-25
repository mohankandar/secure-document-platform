import { apiClient } from '../../api/client';
import type { BusinessAdminIdentifierDraft } from '../../types';
import { SettingsCard, Toggle, Loading, useBusinessAdmin } from './shared';

export default function FieldsArea({ canIdentifiers, canColumns }: { canIdentifiers: boolean; canColumns: boolean }) {
  const { data, error, mutate } = useBusinessAdmin();
  if (!data) return <Loading error={error} retry={() => mutate()} label="Case fields" />;

  return (
    <div className="cf-ad-stack">
      {canIdentifiers && (
        <SettingsCard
          title="Identifiers"
          description="Captured at intake. The primary identifier names the case everywhere."
          value={data.identifiers as BusinessAdminIdentifierDraft[]}
          savedMessage="Identifiers saved."
          onSave={async (d) => mutate(await apiClient.updateBusinessIdentifiers(d), { revalidate: false })}
        >
          {(d, set) => {
            const update = (i: number, patch: Partial<BusinessAdminIdentifierDraft>) => set(d.map((x, j) => (j === i ? { ...x, ...patch } : x)));
            return (
              <div className="cf-ad-table-wrap">
                <table className="cf-ad-table">
                  <thead>
                    <tr><th scope="col">Field</th><th scope="col">Primary</th><th scope="col">Required</th><th scope="col">Searchable</th><th scope="col">Auto-extract</th><th scope="col">Shown</th></tr>
                  </thead>
                  <tbody>
                    {d.map((idf, i) => (
                      <tr key={idf.id ?? i}>
                        <th scope="row">
                          <input className="cf-ad-inline" aria-label="Field label" value={idf.label} onChange={(e) => update(i, { label: e.target.value })} />
                        </th>
                        <td><input type="radio" name="primary-identifier" aria-label={`Make ${idf.label} primary`} checked={idf.primary} onChange={() => set(d.map((x, j) => ({ ...x, primary: j === i, required: j === i ? true : x.required })))} /></td>
                        <td><input type="checkbox" aria-label={`${idf.label} required`} checked={idf.required} disabled={idf.primary} onChange={(e) => update(i, { required: e.target.checked })} /></td>
                        <td><input type="checkbox" aria-label={`${idf.label} searchable`} checked={idf.searchable} onChange={(e) => update(i, { searchable: e.target.checked })} /></td>
                        <td><input type="checkbox" aria-label={`${idf.label} auto-extract`} checked={idf.extractable} onChange={(e) => update(i, { extractable: e.target.checked })} /></td>
                        <td><input type="checkbox" aria-label={`${idf.label} shown`} checked={idf.displayable} onChange={(e) => update(i, { displayable: e.target.checked })} /></td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                {d.some((x) => !x.label.trim()) && <p className="cf-ad-warn">Every identifier needs a label.</p>}
              </div>
            );
          }}
        </SettingsCard>
      )}

      {canColumns && (
        <SettingsCard
          title="Desk columns"
          description="Allowed columns can be turned on by anyone. Default columns are on for new users."
          value={data.dashboardColumns}
          savedMessage="Desk columns saved."
          onSave={async (d) => mutate(await apiClient.updateDashboardColumns(d), { revalidate: false })}
        >
          {(d, set) => (
            <div className="cf-ad-list">
              {[...d].sort((a, b) => a.displayOrder - b.displayOrder).map((col) => (
                <div key={col.key} className="cf-ad-row">
                  <span className="cf-ad-row-label">{col.label}{col.primary && <span className="cf-ad-tag">Primary</span>}</span>
                  <Toggle label="Allowed" checked={col.allowed} disabled={col.primary} onChange={(v) => set(d.map((x) => (x.key === col.key ? { ...x, allowed: v, defaultVisible: v ? x.defaultVisible : false } : x)))} />
                  <Toggle label="Default" checked={col.defaultVisible} disabled={!col.allowed} onChange={(v) => set(d.map((x) => (x.key === col.key ? { ...x, defaultVisible: v } : x)))} />
                </div>
              ))}
            </div>
          )}
        </SettingsCard>
      )}
    </div>
  );
}
