/**
 * Task 0087: a destination's profile and what IT adds to it, in plain words. Pure functions, tested without a browser.
 */
import type { DestinationProfile, DestinationSettings, IntegrationAuthenticationType } from '../../types';

export const emptyDestination = (): DestinationSettings => ({
  profileFile: '', tokenAddress: '', clientId: '', scope: '', audience: '', clientSecretBasic: false, sendAllFields: true,
  fieldMapping: {}, sensitiveFields: [], caseLevel: 'REFUSE', levelRefusalCodes: [], settings: {},
});

/** LexaVault's sign-in methods by the name a profile gives them. */
const SIGN_INS: Record<string, IntegrationAuthenticationType> = {
  NONE: 'NONE', BASIC: 'BASIC', API_KEY: 'API_KEY', BEARER_FIXED: 'JWT', OAUTH2_CLIENT_CREDENTIALS: 'OAUTH2_CLIENT_CREDENTIALS',
};

/** The sign-in methods this profile takes; all of them while no profile is chosen. */
export function signInsOf(profile: DestinationProfile | undefined): IntegrationAuthenticationType[] {
  const all = Object.values(SIGN_INS);
  if (!profile || profile.problems.length) return all;
  return all.filter((a) => profile.signInTypes.some((t) => SIGN_INS[t] === a));
}

/** The choices in the profile list. A damaged file can't be chosen; one that is chosen but gone still shows its name. */
export function profileOptions(profiles: DestinationProfile[], chosen: string): [string, string][] {
  const usable = profiles.filter((p) => !p.problems.length);
  const options: [string, string][] = [['', 'Choose a profile'], ...usable.map((p): [string, string] => [p.file, `${p.name} ${p.version}`])];
  if (chosen && !usable.some((p) => p.file === chosen)) options.push([chosen, `${chosen} (can't be used)`]);
  return options;
}

/** "loan=contract_number, sum=amount" and back. */
export const mappingText = (mapping: Record<string, string>) => Object.entries(mapping).map(([name, key]) => `${name}=${key}`).join(', ');

export function parseMapping(text: string): { mapping: Record<string, string>; problem: string | null } {
  const mapping: Record<string, string> = {};
  for (const part of text.split(/[,\n]/).map((p) => p.trim()).filter(Boolean)) {
    const at = part.indexOf('=');
    const name = part.slice(0, at).trim();
    const key = part.slice(at + 1).trim();
    if (at < 1 || !key) return { mapping, problem: `“${part}” is not “their name=case field key”.` };
    if (mapping[name]) return { mapping, problem: `“${name}” is there twice.` };
    mapping[name] = key;
  }
  return { mapping, problem: null };
}

export const listText = (items: string[]) => items.join(', ');
export const parseList = (text: string) => [...new Set(text.split(/[,\s]+/).filter(Boolean))];

/** What the profile can't carry, so IT knows before the first case stops. */
export function profileWarnings(profile: DestinationProfile | undefined): string[] {
  if (!profile || profile.problems.length) return [];
  const out: string[] = [];
  if (!profile.carriesLevel) out.push('This destination takes no sensitivity level: a document above Standard is not sent.');
  else if (!profile.carriesCaseLevel) out.push('This destination takes a level for each document, but none for the whole case.');
  if (!profile.takesFields) out.push('This destination takes no case fields.');
  return out;
}

/** The settings as they are saved: no empty text, and only what the sign-in uses. */
export function toDestination(d: DestinationSettings, auth: IntegrationAuthenticationType, profile?: DestinationProfile): DestinationSettings {
  const oauth = auth === 'OAUTH2_CLIENT_CREDENTIALS';
  const text = (v: string | null | undefined) => v?.trim() || undefined;
  return {
    ...d,
    tokenAddress: oauth ? text(d.tokenAddress) : undefined,
    clientId: oauth || auth === 'BASIC' ? text(d.clientId) : undefined,
    scope: oauth ? text(d.scope) : undefined,
    audience: oauth ? text(d.audience) : undefined,
    clientSecretBasic: oauth && d.clientSecretBasic,
    sendAllFields: profile && !profile.takesFields ? false : d.sendAllFields,
    profileName: undefined,
    profileVersion: undefined,
  };
}
