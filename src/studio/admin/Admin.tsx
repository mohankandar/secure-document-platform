import { NavLink, Navigate, useParams } from 'react-router-dom';
import type { CurrentUser } from '../../types';
import { CAP } from '../data';
import PolicyArea from './PolicyArea';
import FieldsArea from './FieldsArea';
import IntegrationsArea from './IntegrationsArea';
import IdentityArea from './IdentityArea';
import AuditArea from './AuditArea';
import HealthArea from './HealthArea';
import './admin.css';

type AreaKey = 'policy' | 'fields' | 'integrations' | 'identity' | 'health' | 'audit';

const AREAS: { key: AreaKey; label: string; hint: string; allowed: (caps: string[]) => boolean }[] = [
  { key: 'policy', label: 'Redaction & retention', hint: 'What gets redacted, how long artifacts live, service targets', allowed: (c) => c.includes(CAP.policy) },
  { key: 'fields', label: 'Case fields', hint: 'Identifiers captured at intake and columns on the Desk', allowed: (c) => c.includes(CAP.identifiers) || c.includes(CAP.policy) },
  { key: 'integrations', label: 'Integrations', hint: 'Intake sources and delivery destinations', allowed: (c) => c.includes(CAP.integrations) },
  { key: 'identity', label: 'Sign-in', hint: 'Enterprise identity provider', allowed: (c) => c.includes(CAP.auth) },
  { key: 'health', label: 'System health', hint: 'Processing and storage dependencies', allowed: (c) => c.includes(CAP.technicalAdmin) },
  { key: 'audit', label: 'Audit trail', hint: 'Who changed what, and when', allowed: (c) => c.includes(CAP.audit) },
];

export default function Admin({ caps, me }: { caps: string[]; me: CurrentUser }) {
  const { area } = useParams<{ area?: string }>();
  const visible = AREAS.filter((a) => a.allowed(caps));
  const current = visible.find((a) => a.key === area);

  if (!visible.length) return <Navigate to="/" replace />;
  if (!current) return <Navigate to={`/admin/${visible[0].key}`} replace />;

  return (
    <div className="cf-admin">
      <header className="cf-page-head">
        <div>
          <span className="cf-eyebrow">Admin · {me.tenantName ?? 'Workspace'}</span>
          <h1 className="cf-title">{current.label}</h1>
          <p className="cf-lede">{current.hint}</p>
        </div>
      </header>
      <div className="cf-ad-layout">
        <nav className="cf-ad-rail" aria-label="Admin areas">
          {visible.map((a) => (
            <NavLink key={a.key} to={`/admin/${a.key}`} className={({ isActive }) => `cf-ad-rail-link${isActive ? ' is-active' : ''}`}>
              {a.label}
            </NavLink>
          ))}
        </nav>
        <div className="cf-ad-content">
          {current.key === 'policy' && <PolicyArea />}
          {current.key === 'fields' && <FieldsArea canIdentifiers={caps.includes(CAP.identifiers)} canColumns={caps.includes(CAP.policy)} />}
          {current.key === 'integrations' && <IntegrationsArea />}
          {current.key === 'identity' && <IdentityArea />}
          {current.key === 'health' && <HealthArea />}
          {current.key === 'audit' && <AuditArea />}
        </div>
      </div>
    </div>
  );
}
