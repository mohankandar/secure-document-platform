import { NavLink } from 'react-router-dom';
import HorizontalTabs from './ui/HorizontalTabs';

type Props = {
  business: boolean;
  integrations: boolean;
  authentication: boolean;
  technical: boolean;
  technicalLabel?: string;
};

export default function AdministrationTabs({ business, integrations, authentication, technical, technicalLabel = 'Tenant IT' }: Props) {
  const tabs = [
    business ? { to: '/business-admin', label: 'Business' } : null,
    integrations ? { to: '/integrations', label: 'Integrations' } : null,
    authentication ? { to: '/authentication-admin', label: 'Authentication' } : null,
    technical ? { to: '/platform-admin', label: technicalLabel } : null,
  ].filter((tab): tab is { to: string; label: string } => Boolean(tab));

  if (tabs.length <= 1) return null;
  return (
    <HorizontalTabs ariaLabel="Administration sections" className="administration-tabs">
      {tabs.map((tab) => (
        <NavLink key={tab.to} to={tab.to} className={({ isActive }) => isActive ? 'active' : ''}>{tab.label}</NavLink>
      ))}
    </HorizontalTabs>
  );
}
