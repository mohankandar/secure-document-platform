import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { POLICY_CAPABILITIES, capabilitiesFor } from '../../e2e-oidc/policyCapabilities.ts';

/** Reads the role-to-capability switch straight from the backend policy, so the e2e copy cannot drift (U018). */
function policyFromJava(): Record<string, string[]> {
  const java = readFileSync(new URL('../../../../backend/securedoc-api/src/main/java/com/securedoc/platform/api/security/ProductAuthorizationPolicy.java', import.meta.url), 'utf8');
  const sets: Record<string, string[]> = {};
  for (const m of java.matchAll(/EnumSet<UserCapability>\s+(\w+)\s*=\s*EnumSet\.of\(([^;]*)\);/g)) {
    sets[m[1]] = [...m[2].matchAll(/UserCapability\.(\w+)/g)].map((c) => c[1]);
  }
  const switchBody = java.slice(java.indexOf('switch (role'), java.indexOf('default ->'));
  const roles: Record<string, string[]> = {};
  const cases = [...switchBody.matchAll(/case\s+"(\w+)"\s*->/g)];
  cases.forEach((c, i) => {
    const body = switchBody.slice(c.index! + c[0].length, cases[i + 1]?.index ?? switchBody.length);
    const caps = new Set<string>();
    for (const ref of body.matchAll(/addAll\((\w+)\)/g)) {
      assert.ok(sets[ref[1]], `unknown capability set ${ref[1]}`);
      sets[ref[1]].forEach((x) => caps.add(x));
    }
    for (const one of body.matchAll(/add\(UserCapability\.(\w+)\)/g)) caps.add(one[1]);
    roles[c[1]] = [...caps].sort();
  });
  return roles;
}

test('the e2e capability lists are exactly ProductAuthorizationPolicy', () => {
  const java = policyFromJava();
  assert.deepEqual(Object.keys(POLICY_CAPABILITIES).sort(), Object.keys(java).sort());
  for (const [role, caps] of Object.entries(java)) assert.deepEqual(capabilitiesFor(role), caps, role);
});

test('several roles give the union; an unknown role gives nothing', () => {
  assert.deepEqual(capabilitiesFor('DOCUMENT_PROCESSOR', 'DOCUMENT_SUPERVISOR'), capabilitiesFor('DOCUMENT_SUPERVISOR'));
  assert.deepEqual(capabilitiesFor('ADMIN'), []);
});
