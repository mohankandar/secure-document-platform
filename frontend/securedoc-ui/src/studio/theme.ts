import { useSyncExternalStore } from 'react';

export type Theme = 'light' | 'dark';
/** What the person chose in the account menu: a theme, or "System" (follow the computer's setting). */
export type ThemePreference = Theme | 'system';

const STORAGE_KEY = 'lexavault.theme';
const THEME_COLOR: Record<Theme, string> = { light: '#f2f3f4', dark: '#0d0e10' };
const listeners = new Set<() => void>();

function readStoredTheme(): Theme | null {
  try {
    const stored = window.localStorage.getItem(STORAGE_KEY);
    return stored === 'light' || stored === 'dark' ? stored : null;
  } catch {
    return null;
  }
}

function systemTheme(): Theme {
  return window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}

let current: Theme = typeof window === 'undefined' ? 'light' : readStoredTheme() ?? systemTheme();

function apply(theme: Theme) {
  const root = document.documentElement;
  root.dataset.theme = theme;
  root.style.colorScheme = theme;
  let meta = document.querySelector<HTMLMetaElement>('meta[name="theme-color"]');
  if (!meta) {
    meta = document.createElement('meta');
    meta.name = 'theme-color';
    document.head.appendChild(meta);
  }
  meta.content = THEME_COLOR[theme];
}

if (typeof document !== 'undefined') {
  apply(current);
  // Follow the OS only until the user picks a theme explicitly.
  window.matchMedia?.('(prefers-color-scheme: dark)').addEventListener?.('change', (event) => {
    if (readStoredTheme()) return;
    current = event.matches ? 'dark' : 'light';
    apply(current);
    listeners.forEach((listener) => listener());
  });
}

export function setTheme(theme: Theme) {
  current = theme;
  try {
    window.localStorage.setItem(STORAGE_KEY, theme);
  } catch {
    // Private mode: the choice still applies for this session.
  }
  apply(theme);
  listeners.forEach((listener) => listener());
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function useTheme(): [Theme, (theme: Theme) => void] {
  const theme = useSyncExternalStore(subscribe, () => current, () => current);
  return [theme, setTheme];
}

/** "System" removes the stored choice, so the theme follows the computer's setting again. */
export function setThemePreference(preference: ThemePreference) {
  if (preference !== 'system') {
    setTheme(preference);
    return;
  }
  try {
    window.localStorage.removeItem(STORAGE_KEY);
  } catch {
    // Private mode: nothing was stored.
  }
  current = systemTheme();
  apply(current);
  listeners.forEach((listener) => listener());
}

function preference(): ThemePreference {
  return readStoredTheme() ?? 'system';
}

export function useThemePreference(): [ThemePreference, (preference: ThemePreference) => void] {
  // The snapshot is the stored choice, so choosing "System" re-renders even when the theme shown stays the same.
  const chosen = useSyncExternalStore(subscribe, preference, () => 'system' as ThemePreference);
  return [chosen, setThemePreference];
}
