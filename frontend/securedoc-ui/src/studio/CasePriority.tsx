import { useId, useState } from 'react';
import { useSWRConfig } from 'swr';
import { apiClient } from '../api/client';
import { errorMessage } from './data';
import { useToast } from './toast';
import { PRIORITIES, PRIORITY_FIELD, PRIORITY_NAMES, priorityChoice, priorityName, type PriorityCode } from './words';

/** Task 0073: the case's priority in the case panel; a reviewer changes it, and waiting work moves at once. */
export default function CasePriority({ packageId, priority, onChanged }: { packageId: string; priority?: string; onChanged: () => Promise<unknown> }) {
  const id = useId();
  const toast = useToast();
  const { mutate } = useSWRConfig();
  const [saving, setSaving] = useState(false);

  async function change(next: PriorityCode) {
    setSaving(true);
    try {
      await apiClient.changeCasePriority(packageId, next);
      await Promise.all([onChanged(), mutate('packages'), mutate('review-queue')]);
      toast(`Priority is now ${PRIORITY_NAMES[next]}.`);
    } catch (e) {
      toast(errorMessage(e, "The priority couldn't be changed."), 'danger');
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="cf-field" data-testid="case-priority">
      <label htmlFor={id}><span>{PRIORITY_FIELD.label}</span></label>
      <select id={id} value={priority === 'BULK' ? 'BULK' : priorityChoice(priority)} disabled={saving} onChange={(e) => void change(e.target.value as PriorityCode)}>
        {priority === 'BULK' && <option value="BULK" disabled>{priorityName('BULK')}</option>}
        {PRIORITIES.map((p) => <option key={p} value={p}>{PRIORITY_NAMES[p]}</option>)}
      </select>
    </div>
  );
}
