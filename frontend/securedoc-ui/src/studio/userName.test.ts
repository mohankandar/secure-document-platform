import assert from 'node:assert/strict';
import test from 'node:test';
import { initials, secondNameLine } from './userName.ts';

test('a display name gives the first and last initials', () => {
  assert.equal(initials('Arun Pillai'), 'AP');
  assert.equal(initials('  Mary Ann  Smith '), 'MS');
});

test('one word gives one letter', () => {
  assert.equal(initials('Arun'), 'A');
});

test('logins and emails keep their initials', () => {
  assert.equal(initials('jordan.park@northwind.example'), 'JP');
  assert.equal(initials('dana_reyes'), 'DR');
  assert.equal(initials('sam-lee'), 'SL');
});

test('an empty name falls back to U', () => {
  assert.equal(initials(''), 'U');
  assert.equal(initials('   '), 'U');
});

test('the account menu shows the username only when it differs from the shown name (U020)', () => {
  assert.equal(secondNameLine('Dana Reyes', 'Dana Reyes'), null);
  assert.equal(secondNameLine('Dana', 'dana'), null);
  assert.equal(secondNameLine('Dana Reyes', '  Dana Reyes '), null);
  assert.equal(secondNameLine('J. Park', 'jordan.park@northwind.example'), 'jordan.park@northwind.example');
  assert.equal(secondNameLine('Dana', ''), null);
});
