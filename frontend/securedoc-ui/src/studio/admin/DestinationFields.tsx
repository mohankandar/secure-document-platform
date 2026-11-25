import { useState } from 'react';
import type { DestinationProfile, DestinationSettings, IntegrationAuthenticationType } from '../../types';
import { Notice } from '../atoms';
import { SelectField, TextField, Toggle } from './shared';
import { listText, mappingText, parseList, parseMapping, profileOptions, profileWarnings } from './destinationRules';

/** Task 0087: the destination's profile and what IT adds to it (inside the connection editor's "Where and how to connect"). */
export default function DestinationFields({ value, auth, profiles, unavailable, onChange }: {
  value: DestinationSettings;
  auth: IntegrationAuthenticationType;
  profiles: DestinationProfile[];
  unavailable: boolean;
  onChange: (v: DestinationSettings) => void;
}) {
  const [mapping, setMapping] = useState(mappingText(value.fieldMapping));
  const [sensitive, setSensitive] = useState(listText(value.sensitiveFields));
  const [codes, setCodes] = useState(listText(value.levelRefusalCodes));
  const profile = profiles.find((p) => p.file === value.profileFile);
  const set = (change: Partial<DestinationSettings>) => onChange({ ...value, ...change });
  const oauth = auth === 'OAUTH2_CLIENT_CREDENTIALS';
  const mappingProblem = parseMapping(mapping).problem;
  return <>
    <div className="cf-ad-span">
      <SelectField label="Destination’s profile" value={value.profileFile} options={profileOptions(profiles, value.profileFile)}
        onChange={(v) => set({ profileFile: v })}
        hint="A file the destination’s owner publishes. Put it in LexaVault’s profile folder; it says how the destination works." />
      {unavailable && <Notice tone="danger">The profiles couldn’t be read. Try again.</Notice>}
      {!unavailable && !profiles.length && <p className="cf-ad-hint">No profile is in the folder yet.</p>}
      {profile?.notes.map((n) => <p key={n} className="cf-ad-hint">{n}</p>)}
      {profileWarnings(profile).map((w) => <p key={w} className="cf-ad-hint">{w}</p>)}
      {profiles.filter((p) => p.problems.length).map((p) => <p key={p.file} className="cf-ad-hint">{p.file} can’t be used: {p.problems[0]}</p>)}
    </div>
    {oauth && <>
      <div className="cf-ad-span"><TextField label="Where the token comes from" required mono value={value.tokenAddress || ''} placeholder="https://…" onChange={(v) => set({ tokenAddress: v })}
        hint="Where LexaVault gets its sign-in token." /></div>
      <TextField label="Scope" mono value={value.scope || ''} onChange={(v) => set({ scope: v })} hint="Only if the login system asks for one." />
      <TextField label="Audience" mono value={value.audience || ''} onChange={(v) => set({ audience: v })} hint="Only if the login system asks for one." />
    </>}
    {(oauth || auth === 'BASIC') && (
      <TextField label={oauth ? 'Client ID' : 'User name'} required mono value={value.clientId || ''} onChange={(v) => set({ clientId: v })} />
    )}
    <Toggle label="Send every case field under its own key" checked={value.sendAllFields} disabled={profile ? !profile.takesFields : false}
      onChange={(v) => set({ sendAllFields: v })} />
    <div className="cf-ad-span"><TextField label="Other field names" mono value={mapping} invalid={!!mappingProblem} placeholder="their name=case field key"
      onChange={(v) => { setMapping(v); const p = parseMapping(v); set({ fieldMapping: p.mapping }); }}
      hint={mappingProblem ?? `Separate them with commas.${profile?.fields.length ? ` The destination knows: ${profile.fields.join(', ')} (* is needed).` : ''}`} /></div>
    <TextField label="Sensitive fields that may be sent" mono value={sensitive} onChange={(v) => { setSensitive(v); set({ sensitiveFields: parseList(v) }); }}
      hint="Their keys. A case with another sensitive field is not sent." />
    <TextField label="Codes that refuse a sensitivity level" mono value={codes} onChange={(v) => { setCodes(v); set({ levelRefusalCodes: parseList(v) }); }}
      hint="The destination’s own codes, for example ABC-LEVEL-*." />
    {profile?.carriesLevel && !profile.carriesCaseLevel && (
      <div className="cf-ad-span"><SelectField label="A case above Standard" value={value.caseLevel}
        options={[['REFUSE', 'Don’t send it'], ['ON_EACH_DOCUMENT', 'Send each document with the higher level']]}
        onChange={(v) => set({ caseLevel: v as DestinationSettings['caseLevel'] })}
        hint="The destination takes no level for the whole case." /></div>
    )}
  </>;
}
