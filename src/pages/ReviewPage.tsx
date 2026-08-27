import { useState } from 'react';
import { apiClient } from '../api/client';
import type { ApiError } from '../types';

export default function ReviewPage() {
  const [output, setOutput] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  // Review task fields
  const [packageId, setPackageId] = useState('');
  const [taskId, setTaskId] = useState('');
  const [comment, setComment] = useState('');

  // Finding fields
  const [findingId, setFindingId] = useState('');
  const [findingTaskId, setFindingTaskId] = useState('');
  const [findingReason, setFindingReason] = useState('');
  const [bbLeft, setBbLeft] = useState('0.1');
  const [bbTop, setBbTop] = useState('0.1');
  const [bbWidth, setBbWidth] = useState('0.3');
  const [bbHeight, setBbHeight] = useState('0.05');

  const exec = async (label: string, fn: () => Promise<unknown>) => {
    setError(null);
    setOutput(null);
    try {
      const result = await fn();
      setOutput(`${label}: ${JSON.stringify(result, null, 2)}`);
    } catch (err) {
      const apiErr = err as ApiError;
      setError(`${label} failed: ${apiErr.message || 'Unknown error'}${apiErr.correlationId ? ` (${apiErr.correlationId})` : ''}`);
    }
  };

  return (
    <div>
      <h1>Review Operations</h1>

      {error && <div className="error-msg">{error}</div>}
      {output && <div className="success-msg" style={{ whiteSpace: 'pre-wrap', fontFamily: 'monospace', fontSize: '0.8rem' }}>{output}</div>}

      <div className="card">
        <h2 style={{ fontSize: '1rem', marginBottom: '0.75rem' }}>Review Task</h2>
        <div className="form-group">
          <label>Package ID</label>
          <input value={packageId} onChange={(e) => setPackageId(e.target.value)} placeholder="UUID" />
        </div>
        <div className="form-group">
          <label>Task ID (for assign/start/complete)</label>
          <input value={taskId} onChange={(e) => setTaskId(e.target.value)} placeholder="UUID" />
        </div>
        <div className="form-group">
          <label>Comment (for complete)</label>
          <input value={comment} onChange={(e) => setComment(e.target.value)} placeholder="Optional" />
        </div>
        <div className="inline-actions">
          <button className="primary" onClick={() => exec('Create Task', () => apiClient.createReviewTask(packageId))}>Create</button>
          <button className="secondary" onClick={() => exec('Assign to local demo actor', () => apiClient.assignReviewTask(taskId))}>Assign to current actor</button>
          <button className="secondary" onClick={() => exec('Start', () => apiClient.startReviewTask(taskId))}>Start</button>
          <button className="secondary" onClick={() => exec('Complete', () => apiClient.completeReviewTask(taskId, comment))}>Complete</button>
        </div>
      </div>

      <div className="card">
        <h2 style={{ fontSize: '1rem', marginBottom: '0.75rem' }}>Finding Actions</h2>
        <div className="form-group">
          <label>Finding ID</label>
          <input value={findingId} onChange={(e) => setFindingId(e.target.value)} placeholder="UUID" />
        </div>
        <div className="form-group">
          <label>Review Task ID</label>
          <input value={findingTaskId} onChange={(e) => setFindingTaskId(e.target.value)} placeholder="UUID" />
        </div>
        <div className="form-group">
          <label>Reason</label>
          <input value={findingReason} onChange={(e) => setFindingReason(e.target.value)} placeholder="Optional reason" />
        </div>
        <div className="inline-actions">
          <button className="primary" onClick={() => exec('Confirm', () => apiClient.confirmFinding(findingId, findingTaskId, findingReason))}>Confirm</button>
          <button className="secondary" onClick={() => exec('Dismiss', () => apiClient.dismissFinding(findingId, findingTaskId, findingReason))}>Dismiss</button>
        </div>

        <div style={{ marginTop: '1rem' }}>
          <label style={{ fontSize: '0.8rem', fontWeight: 600, color: '#636e72' }}>Bounding Box (normalized 0.0-1.0)</label>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr 1fr', gap: '0.5rem', marginTop: '0.25rem' }}>
            <input placeholder="left" value={bbLeft} onChange={(e) => setBbLeft(e.target.value)} />
            <input placeholder="top" value={bbTop} onChange={(e) => setBbTop(e.target.value)} />
            <input placeholder="width" value={bbWidth} onChange={(e) => setBbWidth(e.target.value)} />
            <input placeholder="height" value={bbHeight} onChange={(e) => setBbHeight(e.target.value)} />
          </div>
          <button className="secondary" style={{ marginTop: '0.5rem' }} onClick={() =>
            exec('Update BBox', () => apiClient.updateFindingBoundingBox(findingId, findingTaskId, {
              left: parseFloat(bbLeft), top: parseFloat(bbTop),
              width: parseFloat(bbWidth), height: parseFloat(bbHeight),
            }))
          }>Update Bounding Box</button>
        </div>
      </div>
    </div>
  );
}
