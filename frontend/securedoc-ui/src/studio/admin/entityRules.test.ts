import { test } from 'node:test';
import assert from 'node:assert/strict';
import { entityName, entityStatus, timeZoneNames, timeZoneText } from './entityRules.ts';

const names = (slot: { status: string; current: boolean }) => entityStatus(slot).actions.map((a) => a.action);

test('what can be done follows the status, and never suspends the entity you are in', () => {
  assert.deepEqual(names({ status: 'NOT_ACTIVATED', current: false }), ['activate']);
  assert.deepEqual(names({ status: 'ACTIVE', current: false }), ['rename', 'timeZone', 'suspend']);
  assert.deepEqual(names({ status: 'ACTIVE', current: true }), ['rename', 'timeZone']);
  assert.deepEqual(names({ status: 'SUSPENDED', current: false }), ['rename', 'timeZone', 'resume']);
  assert.deepEqual(names({ status: 'NOT_IN_LICENCE', current: false }), []);
  assert.deepEqual(names({ status: 'SOMETHING_NEW', current: false }), []);
});

test('names and time zones read in plain words', () => {
  assert.equal(entityName({ name: null }), 'Not named yet');
  assert.equal(entityName({ name: ' Northwind ' }), 'Northwind');
  assert.equal(timeZoneText({ timeZone: 'Europe/London', customerTimeZone: 'UTC' }), 'Europe/London');
  assert.equal(timeZoneText({ timeZone: null, customerTimeZone: 'UTC' }), 'UTC (the customer’s)');
  assert.equal(timeZoneText({ timeZone: null, customerTimeZone: null }), 'Not set');
});

test('the list holds UTC and the one already chosen, once each', () => {
  const zones = timeZoneNames('Mars/Olympus');
  assert.ok(zones.includes('UTC') && zones.includes('America/New_York') && zones.includes('Mars/Olympus'));
  assert.equal(new Set(zones).size, zones.length);
});
