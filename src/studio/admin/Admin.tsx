import { useEffect, useRef } from 'react';
import { NavLink, Navigate, useParams } from 'react-router-dom';
import type { CurrentUser } from '../../types';
import { CAP } from '../data';
import PolicyArea from './PolicyArea';
import FieldsArea from './FieldsArea';
import IntegrationsArea from './IntegrationsArea';
import IdentityArea from './IdentityArea';
import AuditArea from './AuditArea';
import HealthArea from './HealthArea';
import RoutingRules from './RoutingRules';
import './admin.css';

type AreaKey = 'policy' | 'fields' | 'integrations' | 'identity' | 'health' | 'audit';

const AREAS: { key: AreaKey; label: string; short: string; hint: string; allowed: (caps: string[]) => boolean }[] = [
  { key: 'policy', label: 'Redaction & retention', short: 'Redaction', hint: 'What gets redacted, how long artifacts live, and the service targets Reports measures against.', allowed: (c) => c.includes(CAP.policy) },
  { key: 'fields', label: 'Case fields', short: 'Fields', hint: 'Identifiers captured at intake and the columns shown on the Desk.', allowed: (c) => c.includes(CAP.identifiers) || c.includes(CAP.policy) },
  { key: 'integrations', label: 'Integrations', short: 'Integrations', hint: 'Intake sources and delivery destinations, with polling, testing and routing.', allowed: (c) => c.includes(CAP.integrations) },
  { key: 'identity', label: 'Sign-in', short: 'Sign-in', hint: 'Enterprise identity provider: draft, test, activate and roll back.', allowed: (c) => c.includes(CAP.auth) },
  { key: 'health', label: 'System health', short: 'Health', hint: 'Processing and storage dependencies.', allowed: (c) => c.includes(CAP.technicalAdmin) },
  { key: 'audit', label: 'Audit trail', short: 'Audit', hint: 'Who changed what, and when.', allowed: (c) => c.includes(CAP.audit) },
];

export default function Admin({ caps, me }: { caps: string[]; me: CurrentUser }) {
  const { area } = useParams<{ area?: string }>();
  const visible = AREAS.filter((a) => a.allowed(caps));
  const current = visible.find((a) => a.key === area);
  const tabsRef = useRef<HTMLElement>(null);

  useEffect(() => {
    tabsRef.current?.querySelector<HTMLElement>('[aria-current="page"]')?.scrollIntoView({ block: 'nearest', inline: 'nearest' });
  }, [current?.key]);

  if (!visible.length) return <Navigate to="/" replace />;
  if (!current) return <Navigate to={`/admin/${visible[0].key}`} replace />;

  return (
    <div className="cf-admin">
      <header className="cf-ad-head">
        <span className="cf-eyebrow">{me.tenantName ?? 'Workspace'}</span>
        <h1 className="cf-title">Administration</h1>
      </header>

      <nav ref={tabsRef} className="cf-ad-tabs" aria-label="Administration areas">
        {visible.map((a) => (
          <NavLink key={a.key} to={`/admin/${a.key}`} className={({ isActive }) => `cf-ad-tab${isActive ? ' is-active' : ''}`}>
            <span className="cf-ad-tab-full">{a.label}</span>
            <span className="cf-ad-tab-short" aria-hidden="true">{a.short}</span>
          </NavLink>
        ))}
      </nav>

      <section className="cf-ad-content" aria-labelledby="cf-ad-area-title">
        <h2 id="cf-ad-area-title" className="sr-only">{current.label}</h2>
        <p className="cf-ad-context">{current.hint}</p>
        {current.key === 'policy' && (caps.includes(CAP.integrations) ? <PolicyArea /> : <div className="cf-ad-stack"><PolicyArea /><RoutingRules /></div>)}
        {current.key === 'fields' && <FieldsArea canIdentifiers={caps.includes(CAP.identifiers)} canColumns={caps.includes(CAP.policy)} />}
        {current.key === 'integrations' && <IntegrationsArea />}
        {current.key === 'identity' && <IdentityArea />}
        {current.key === 'health' && <HealthArea />}
        {current.key === 'audit' && <AuditArea />}
      </section>
    </div>
  );
}
