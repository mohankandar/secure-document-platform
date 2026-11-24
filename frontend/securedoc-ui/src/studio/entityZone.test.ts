import { test } from 'node:test';
import assert from 'node:assert/strict';
import { browserZone, dayFrom, dayIn, dayText, plusDays, startOfDay, zoneOrBrowser } from './entityZone.ts';

const start = (text: string, zone: string) => startOfDay(dayFrom(text)!, zone).toISOString();

test('a day starts at its own midnight, on the days the clocks change too', () => {
  assert.equal(start('2026-03-08', 'America/New_York'), '2026-03-08T05:00:00.000Z');
  assert.equal(start('2026-03-09', 'America/New_York'), '2026-03-09T04:00:00.000Z'); // a 23-hour day before
  assert.equal(start('2026-11-01', 'America/New_York'), '2026-11-01T04:00:00.000Z');
  assert.equal(start('2026-11-02', 'America/New_York'), '2026-11-02T05:00:00.000Z'); // a 25-hour day before
  assert.equal(start('2026-06-01', 'Pacific/Kiritimati'), '2026-05-31T10:00:00.000Z'); // UTC+14
  assert.equal(start('2026-06-01', 'Pacific/Pago_Pago'), '2026-06-01T11:00:00.000Z'); // UTC-11
  assert.equal(start('2026-06-01', 'Asia/Kolkata'), '2026-05-31T18:30:00.000Z');
  assert.equal(start('2026-06-01', 'UTC'), '2026-06-01T00:00:00.000Z');
  // Midnight itself does not exist on this day in Beirut: the day starts at 01:00.
  assert.equal(dayText(dayIn(startOfDay({ y: 2026, m: 3, d: 29 }, 'Asia/Beirut'), 'Asia/Beirut')), '2026-03-29');
});

test('every day of a year starts in that day, one moment after the day before', () => {
  for (const zone of ['America/New_York', 'Europe/London', 'Australia/Lord_Howe', 'America/Havana']) {
    let last = 0;
    for (let i = 0; i < 366; i++) {
      const day = plusDays({ y: 2026, m: 1, d: 1 }, i);
      const at = startOfDay(day, zone);
      assert.equal(dayText(dayIn(at, zone)), dayText(day), zone);
      assert.notEqual(dayText(dayIn(new Date(at.getTime() - 1000), zone)), dayText(day), `${zone} ${dayText(day)} starts earlier`);
      assert.ok(at.getTime() > last);
      last = at.getTime();
    }
  }
});

test('days are read and added as calendar days', () => {
  assert.equal(dayText(plusDays({ y: 2026, m: 2, d: 28 }, 1)), '2026-03-01');
  assert.equal(dayFrom('2026-02-30'), null);
  assert.equal(dayFrom('yesterday'), null);
});

test('without a zone, or with one this browser does not know, the browser’s own', () => {
  assert.equal(zoneOrBrowser(null), browserZone());
  assert.equal(zoneOrBrowser('Mars/Olympus'), browserZone());
  assert.equal(zoneOrBrowser('Europe/London'), 'Europe/London');
});
