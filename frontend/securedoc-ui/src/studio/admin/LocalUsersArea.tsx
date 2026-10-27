import { useState } from 'react';
import useSWR from 'swr';
import { apiClient } from '../../api/client';
import { Notice } from '../atoms';

type DemoUser = { actorId: string; displayName: string; roleName: string };
const ROLES = [
  { value: 'DOCUMENT_PROCESSOR', label: 'Document Processor' },
  { value: 'REPORT_VIEWER', label: 'Report Viewer' },
];

export default function LocalUsersArea() {
  const { data, error, mutate } = useSWR<DemoUser[]>('local-demo-users', () => apiClient.getLocalDemoUsers());
  const [name, setName] = useState('');
  const [role, setRole] = useState('DOCUMENT_PROCESSOR');
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState('');
  const [failure, setFailure] = useState('');

  async function create(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!name.trim() || saving) return;
    setSaving(true);
    setMessage('');
    setFailure('');
    try {
      const actorId = crypto.randomUUID();
      await apiClient.assignLocalDemoUser(actorId, name.trim(), role);
      await mutate();
      setName('');
      setMessage('Simulated user saved. Assignments are enforced by the LOCAL backend.');
    } catch {
      setFailure('Could not save the simulated user. Check your tenant IT permissions and backend.');
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="cf-ad-stack">
      <div className="cf-card">
        <h3>LOCAL simulated Users & Access</h3>
        <p>These are development identities only. Enterprise users remain governed by the identity provider. Only low-privilege roles may be delegated here.</p>
        <form onSubmit={create} className="cf-ad-stack">
          <label className="cf-field">Display name
            <input required maxLength={120} value={name} onChange={(e) => setName(e.target.value)} placeholder="Demo operator" />
          </label>
          <label className="cf-field">Role
            <select value={role} onChange={(e) => setRole(e.target.value)}>
              {ROLES.map((item) => <option key={item.value} value={item.value}>{item.label}</option>)}
            </select>
          </label>
          <button className="cf-btn" data-variant="solid" type="submit" disabled={saving}>{saving ? 'Saving…' : 'Create simulated user'}</button>
        </form>
        {message && <p role="status">{message}</p>}
        {failure && <Notice tone="danger">{failure}</Notice>}
      </div>
      <div className="cf-card">
        <h3>Simulated tenant users</h3>
        {error && <Notice tone="danger">Could not load local users.</Notice>}
        {!data && !error && <p>Loading users…</p>}
        {data?.length === 0 && <p>No simulated users created yet.</p>}
        {data?.map((user) => (
          <div key={user.actorId} className="cf-ad-row">
            <strong>{user.displayName}</strong>
            <p>{ROLES.find((item) => item.value === user.roleName)?.label ?? user.roleName}</p>
            <code>{user.actorId}</code>
          </div>
        ))}
      </div>
    </div>
  );
}
