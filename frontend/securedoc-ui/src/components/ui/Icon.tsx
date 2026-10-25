import type { ReactNode, SVGProps } from 'react';

export type IconName = 'dashboard' | 'upload' | 'scan' | 'review' | 'reports' | 'admin' | 'integrations' | 'support' | 'audit' | 'menu' | 'chevronLeft' | 'chevronRight' | 'chevronDown' | 'chevronUp' | 'user' | 'refresh' | 'palette' | 'logout' | 'search' | 'close' | 'more' | 'check' | 'arrowUp' | 'arrowDown' | 'columns' | 'shield' | 'building' | 'inbox';

const paths: Record<IconName, ReactNode> = {
  dashboard: <><rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/></>,
  upload: <><path d="M12 16V4"/><path d="m7 9 5-5 5 5"/><path d="M5 20h14"/></>,
  scan: <><path d="M4 7V4h3"/><path d="M17 4h3v3"/><path d="M20 17v3h-3"/><path d="M7 20H4v-3"/><path d="M7 12h10"/></>,
  review: <><path d="M9 11l2 2 4-4"/><path d="M5 3h14v18H5z"/></>,
  reports: <><path d="M4 20V10"/><path d="M10 20V4"/><path d="M16 20v-7"/><path d="M22 20H2"/></>,
  admin: <><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.9l.1.1-2.8 2.8-.1-.1a1.7 1.7 0 0 0-1.9-.3 1.7 1.7 0 0 0-1 1.5V21h-4v-.1a1.7 1.7 0 0 0-1-1.5 1.7 1.7 0 0 0-1.9.3l-.1.1L4.2 17l.1-.1a1.7 1.7 0 0 0 .3-1.9 1.7 1.7 0 0 0-1.5-1H3v-4h.1a1.7 1.7 0 0 0 1.5-1 1.7 1.7 0 0 0-.3-1.9L4.2 7 7 4.2l.1.1A1.7 1.7 0 0 0 9 4.6a1.7 1.7 0 0 0 1-1.5V3h4v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.9-.3l.1-.1L19.8 7l-.1.1a1.7 1.7 0 0 0-.3 1.9 1.7 1.7 0 0 0 1.5 1h.1v4h-.1a1.7 1.7 0 0 0-1.5 1z"/></>,
  integrations: <><path d="M8 12h8"/><path d="M12 8v8"/><circle cx="6" cy="6" r="3"/><circle cx="18" cy="18" r="3"/></>,
  support: <><circle cx="12" cy="12" r="9"/><path d="M9.7 9a2.5 2.5 0 1 1 4.5 1.5c-.9.9-2.2 1.3-2.2 3"/><path d="M12 17h.01"/></>,
  audit: <><path d="M5 4h14v16H5z"/><path d="M8 8h8M8 12h8M8 16h5"/></>,
  menu: <><path d="M4 7h16M4 12h16M4 17h16"/></>,
  chevronLeft: <path d="m15 18-6-6 6-6"/>,
  chevronRight: <path d="m9 18 6-6-6-6"/>,
  chevronDown: <path d="m6 9 6 6 6-6"/>,
  chevronUp: <path d="m18 15-6-6-6 6"/>,
  search: <><circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/></>,
  close: <><path d="M18 6 6 18"/><path d="m6 6 12 12"/></>,
  more: <><circle cx="12" cy="5" r="1"/><circle cx="12" cy="12" r="1"/><circle cx="12" cy="19" r="1"/></>,
  check: <path d="M20 6 9 17l-5-5"/>,
  arrowUp: <><path d="M12 19V5"/><path d="m5 12 7-7 7 7"/></>,
  arrowDown: <><path d="M12 5v14"/><path d="m19 12-7 7-7-7"/></>,
  columns: <><rect x="3" y="4" width="18" height="16" rx="2"/><path d="M9 4v16M15 4v16"/></>,
  shield: <><path d="M12 3 4 6v6c0 4.5 3.4 8.3 8 9 4.6-.7 8-4.5 8-9V6z"/><path d="m9 12 2 2 4-4"/></>,
  building: <><path d="M4 21V5a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2v16"/><path d="M16 9h2a2 2 0 0 1 2 2v10"/><path d="M8 7h4M8 11h4M8 15h4M3 21h18"/></>,
  inbox: <><path d="M22 12h-6l-2 3h-4l-2-3H2"/><path d="M5.5 5h13L22 12v6a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2v-6z"/></>,
  user: <><circle cx="12" cy="8" r="4"/><path d="M4 21a8 8 0 0 1 16 0"/></>,
  refresh: <><path d="M20 6v5h-5"/><path d="M19 11a7 7 0 1 0 1 4"/></>,
  logout: <><path d="M10 5H5v14h5"/><path d="M14 8l4 4-4 4"/><path d="M18 12H9"/></>,
  palette: <><path d="M12 3a9 9 0 0 0 0 18h1.5a2 2 0 0 0 0-4H12a2 2 0 0 1 0-4h5a4 4 0 0 0 4-4 6 6 0 0 0-2-4.4A10 10 0 0 0 12 3z"/><circle cx="7.5" cy="10" r=".7"/><circle cx="9.5" cy="6.8" r=".7"/><circle cx="14" cy="6.5" r=".7"/></>,
};

export default function Icon({ name, ...props }: SVGProps<SVGSVGElement> & { name: IconName }) {
  return <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" {...props}>{paths[name]}</svg>;
}
