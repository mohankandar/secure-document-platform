export type DemoLatency = 'off' | 'normal' | 'slow';

export interface DemoSettings {
  latency: DemoLatency;
  outage: boolean;
  scannerOffline: boolean;
}

const SETTINGS_KEY = 'lexavault.demo.settings';
const DEFAULTS: DemoSettings = { latency: 'normal', outage: false, scannerOffline: false };

export function getDemoSettings(): DemoSettings {
  try {
    return { ...DEFAULTS, ...JSON.parse(localStorage.getItem(SETTINGS_KEY) || '{}') };
  } catch {
    return DEFAULTS;
  }
}

export function setDemoSettings(patch: Partial<DemoSettings>): DemoSettings {
  const next = { ...getDemoSettings(), ...patch };
  localStorage.setItem(SETTINGS_KEY, JSON.stringify(next));
  return next;
}
