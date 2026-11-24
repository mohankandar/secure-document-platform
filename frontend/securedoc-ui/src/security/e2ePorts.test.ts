import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { DEFAULT_PORT_BASE, portShift } from '../../e2ePorts.ts';

const ui = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');

test('task 0068: the ports stay as they were without LV_E2E_PORT_BASE, move with it, and a wrong value is refused', () => {
  assert.equal(portShift(undefined), process.env.LV_E2E_PORT_BASE ? Number(process.env.LV_E2E_PORT_BASE) - DEFAULT_PORT_BASE : 0);
  assert.equal(portShift(''), 0);
  assert.equal(portShift('15175'), 0);
  assert.equal(portShift('15275'), 100);
  for (const wrong of ['x', '15275.5', '80', '70000', '-1']) assert.throws(() => portShift(wrong), /LV_E2E_PORT_BASE/);
});

test('task 0068: every suite that starts a server takes its port from e2ePorts.ts and never reuses one it did not start', () => {
  const configs = readdirSync(ui).filter((f) => /^playwright\..*config\.ts$/.test(f));
  let servers = 0;
  for (const file of configs) {
    const source = readFileSync(resolve(ui, file), 'utf8');
    if (!source.includes('webServer')) continue;
    servers++;
    assert.match(source, /const PORT = E2E_PORTS\./, file);
    assert.match(source, /reuseExistingServer: false/, file);
  }
  assert.equal(servers, 4);
});
