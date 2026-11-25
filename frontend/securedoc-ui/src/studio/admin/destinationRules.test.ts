import test from 'node:test';
import assert from 'node:assert/strict';
import { emptyDestination, mappingText, parseList, parseMapping, profileOptions, profileWarnings, signInsOf, toDestination } from './destinationRules.ts';
import type { DestinationProfile } from '../../types';

const profile = (p: Partial<DestinationProfile> = {}): DestinationProfile => ({
  file: 'archive.json', name: 'records-archive-v1', version: '1.0.0', description: 'An archive', signInTypes: ['OAUTH2_CLIENT_CREDENTIALS'],
  fields: [], takesFields: true, otherFields: true, carriesLevel: true, carriesCaseLevel: false, notes: [], problems: [], ...p,
});

test('only the sign-in methods the profile takes are offered; a damaged profile limits nothing', () => {
  assert.deepEqual(signInsOf(profile()), ['OAUTH2_CLIENT_CREDENTIALS']);
  assert.deepEqual(signInsOf(profile({ signInTypes: ['BEARER_FIXED', 'MUTUAL_TLS'] })), ['JWT']);
  assert.equal(signInsOf(undefined).length, 5);
});

test('a damaged profile can’t be chosen, and a chosen one that is gone still shows', () => {
  const bad = profile({ file: 'bad.json', name: null, version: null, problems: ['INT_PROFILE_SCHEMA'] });
  assert.deepEqual(profileOptions([profile(), bad], 'bad.json').map(([, label]) => label),
    ['Choose a profile', 'records-archive-v1 1.0.0', "bad.json (can't be used)"]);
});

test('the field mapping is read and written as text, and a wrong line says which', () => {
  assert.deepEqual(parseMapping('loan=contract_number,\n sum = amount').mapping, { loan: 'contract_number', sum: 'amount' });
  assert.equal(mappingText({ loan: 'contract_number', sum: 'amount' }), 'loan=contract_number, sum=amount');
  assert.match(parseMapping('loan').problem ?? '', /loan/);
  assert.match(parseMapping('a=b, a=c').problem ?? '', /twice/);
  assert.deepEqual(parseList('a, b  a'), ['a', 'b']);
});

test('what the destination can’t carry is said', () => {
  assert.deepEqual(profileWarnings(profile({ carriesCaseLevel: true })), []);
  assert.match(profileWarnings(profile())[0], /none for the whole case/);
  assert.equal(profileWarnings(profile({ carriesLevel: false, takesFields: false })).length, 2);
});

test('saved without what the sign-in does not use', () => {
  const d = { ...emptyDestination(), profileFile: 'archive.json', tokenAddress: ' https://login/token ', clientId: 'lexavault', scope: '' };
  const oauth = toDestination(d, 'OAUTH2_CLIENT_CREDENTIALS', profile());
  assert.equal(oauth.tokenAddress, 'https://login/token');
  assert.equal(oauth.scope, undefined);
  assert.equal(toDestination(d, 'API_KEY', profile()).tokenAddress, undefined);
  assert.equal(toDestination(d, 'API_KEY', profile({ takesFields: false })).sendAllFields, false);
});
