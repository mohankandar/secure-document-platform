import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { apiClient } from '../api/client';
import type { EffectiveBranding } from '../types';

const FALLBACK: EffectiveBranding = {
  tenantId: null,
  applicationName: 'LexaVault',
  shortName: 'LexaVault',
  organizationName: null,
  pageTitle: 'LexaVault',
  accentColor: '#315CF4',
  secondaryColor: '#6D5AE6',
  primaryLogoDataUrl: null,
  compactLogoDataUrl: null,
  faviconDataUrl: null,
};

type BrandingContextValue = { branding: EffectiveBranding; refreshBranding: () => Promise<void> };
const BrandingContext = createContext<BrandingContextValue>({ branding: FALLBACK, refreshBranding: async () => {} });

function hexToRgb(hex: string) {
  const value = hex.replace('#', '');
  return `${parseInt(value.slice(0, 2), 16)} ${parseInt(value.slice(2, 4), 16)} ${parseInt(value.slice(4, 6), 16)}`;
}

export function BrandingProvider({ children }: { children: ReactNode }) {
  const [branding, setBranding] = useState<EffectiveBranding>(FALLBACK);

  const refreshBranding = useCallback(async () => {
    try {
      setBranding(await apiClient.getCurrentBranding());
    } catch {
      setBranding(FALLBACK);
    }
  }, []);

  useEffect(() => { void refreshBranding(); }, [refreshBranding]);

  useEffect(() => {
    const root = document.documentElement;
    root.style.setProperty('--brand-accent', branding.accentColor || FALLBACK.accentColor);
    root.style.setProperty('--brand-accent-rgb', hexToRgb(branding.accentColor || FALLBACK.accentColor));
    root.style.setProperty('--brand-secondary', branding.secondaryColor || FALLBACK.secondaryColor);
    document.title = branding.pageTitle || branding.applicationName || FALLBACK.pageTitle;
    let favicon = document.querySelector<HTMLLinkElement>('link[rel="icon"][data-runtime-branding]');
    if (branding.faviconDataUrl) {
      if (!favicon) {
        favicon = document.createElement('link'); favicon.rel = 'icon'; favicon.dataset.runtimeBranding = 'true'; document.head.appendChild(favicon);
      }
      favicon.href = branding.faviconDataUrl;
    } else if (favicon) favicon.remove();
  }, [branding]);

  const value = useMemo(() => ({ branding, refreshBranding }), [branding, refreshBranding]);
  return <BrandingContext.Provider value={value}>{children}</BrandingContext.Provider>;
}

export function useBranding() { return useContext(BrandingContext); }
