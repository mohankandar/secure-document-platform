import { useId, useState } from 'react';
import useSWR from 'swr';
import { apiClient } from '../api/client';
import { errorMessage } from './data';
import { useToast } from './toast';
import { CASE_INSTRUCTIONS } from './words';

/** Task 0065: the case's instructions for the AI. Shown only where the entity switched them on; kept in this component only. */
export default function CaseInstructions({ packageId }: { packageId: string }) {
  const id = useId();
  const toast = useToast();
  // Document text: asked for each time the panel opens, and not kept in the shared cache.
  const { data, mutate } = useSWR(['case-instructions', packageId, id], () => apiClient.caseInstructions(packageId), { revalidateOnFocus: false });
  const [draft, setDraft] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  if (!data?.enabled) return null;
  const text = draft ?? data.text;

  async function save() {
    setSaving(true);
    try {
      const saved = await apiClient.changeCaseInstructions(packageId, text);
      await mutate(saved, { revalidate: false });
      setDraft(null);
      toast(CASE_INSTRUCTIONS.saved);
    } catch (e) {
      toast(errorMessage(e, CASE_INSTRUCTIONS.notSaved), 'danger');
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="cf-field" data-testid="case-instructions">
      <label htmlFor={id}><span>{CASE_INSTRUCTIONS.label}</span></label>
      <textarea id={id} rows={3} maxLength={CASE_INSTRUCTIONS.max} value={text} disabled={saving} aria-describedby={`${id}-hint`} onChange={(e) => setDraft(e.target.value)} />
      <span id={`${id}-hint`} className="cf-field-hint">{CASE_INSTRUCTIONS.caseHint}</span>
      {text !== data.text && <button type="button" className="cf-btn" disabled={saving} onClick={() => void save()}>{CASE_INSTRUCTIONS.save}</button>}
    </div>
  );
}
