import { test } from 'node:test';
import assert from 'node:assert/strict';
import { changedByWords, draftProblem, draftStatus } from './signInRules.ts';

const ok = { providerId: 'okta', issuerUri: 'https://login.example/oauth2', browserClientId: '', apiAudience: 'api' };

test('a draft is sent only when its required fields look right', () => {
  assert.equal(draftProblem(ok), null);
  for (const issuerUri of ['', 'login.example', 'ftp://x', 'https://login.example/?a=1', 'https://a b']) assert.ok(draftProblem({ ...ok, issuerUri }), issuerUri);
  assert.ok(draftProblem({ ...ok, providerId: ' ' }));
  assert.ok(draftProblem({ ...ok, apiAudience: '' }));
});

test('statuses and who changed it read in plain words, anything unknown too', () => {
  assert.deepEqual(['DRAFT', 'VALIDATED', 'ACTIVE', 'DISABLED', 'NEW'].map(draftStatus), ['Draft, not tested', 'Draft, tested', 'In use', 'Not in use', 'Unknown']);
  assert.deepEqual(['SETUP', 'APP', 'MIGRATION', 'X'].map((source) => changedByWords({ source, changedBy: 'id' })), ['Setup', 'An IT Admin', 'The identity migration', 'LexaVault']);
});
