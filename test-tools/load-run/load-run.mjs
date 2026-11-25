/**
 * Task 0067: a load run on one server. Many small documents (one page, every second one a picture that needs OCR) are
 * sent as cases of 10; the workers per stage, the learned AI limit and the memory are read while they are processed.
 *
 *   node test-tools/load-run/load-run.mjs [documents=400] [heap=4g] [memoryLimitMb=8192] [aiMs=120]
 *
 * A THROWAWAY database container (lexavault-0067-load-pg) and a mock AI server are started here and removed at the end;
 * nothing else is touched. The mock works on 5 requests at once and queues the rest, so the learned limit should settle
 * near 5. The documents hold fake test values only; the database password is made per run and never printed.
 *
 * Checks: every case is exported; Text reaches its highest and every stage comes back to its lowest; the memory of the
 * app and its OCR processes stays under the limit; the learned AI limit is never above the mock's 5 plus the one tried.
 *
 * Task 0078: once everything is sent, the queue's ETA is read too and compared with the real time until the last job
 * ended. It must be within 20% a quarter and half of the way through the drain.
 */
import { spawn, spawnSync, execFileSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { mkdirSync, openSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const documents = Number(process.argv[2] ?? 400);
const heap = process.argv[3] ?? '4g';
const memoryLimitMb = Number(process.argv[4] ?? 8192);
const PER_CASE = 10;
const MOCK_AT_ONCE = 5;
const MOCK_MS = Number(process.argv[5] ?? 120);
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
const jar = join(root, 'backend', 'securedoc-api', 'target', 'securedoc-api-0.1.0-SNAPSHOT.jar');
const container = 'lexavault-0067-load-pg';
const [dbPort, appPort, aiPort] = [55468, 18067, 11567];
const base = `http://localhost:${appPort}`;
const model = 'qwen3.5:9b';
const run = join(tmpdir(), 'lexavault-0067-load', new Date().toISOString().replace(/[:.]/g, '-'));
mkdirSync(join(run, 'storage'), { recursive: true });
const password = randomUUID().replaceAll('-', '');
const headers = {
  'X-Tenant-Id': '30216241-b724-5a3a-aef3-3034fd546d17',
  'X-Actor-Id': '00000000-0000-0000-0000-000000000099',
  'X-Actor-Roles': 'DOCUMENT_PROCESSOR,TENANT_IT_ADMIN',
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const sql = (q) => execFileSync('docker', ['exec', container, 'psql', '-U', 'securedoc', '-d', 'securedoc', '-tA', '-c', q], { encoding: 'utf8' }).trim();

/* ---------- the mock AI server ---------- */
let working = 0;
let mockMost = 0;
const waiting = [];
const answer = JSON.stringify({ message: { content: JSON.stringify({ findings: [
  { pageNumber: 1, entityType: 'SSN', matchedText: '412-55-7531', contextText: '', confidence: 0.97 },
  { pageNumber: 1, entityType: 'CREDIT_CARD_NUMBER', matchedText: '4000 0566 5566 5556', contextText: '', confidence: 0.97 },
  { pageNumber: 1, entityType: 'DATE_OF_BIRTH', matchedText: '03/14/1962', contextText: '', confidence: 0.97 }] }) }, done_reason: 'stop' });
function work(reply) {
  working++;
  setTimeout(() => {
    reply();
    working--;
    if (waiting.length) work(waiting.shift());
  }, MOCK_MS * (0.85 + 0.3 * Math.random()));
}
const mock = createServer((req, res) => {
  req.resume().on('end', () => {
    const send = (body) => res.writeHead(200, { 'Content-Type': 'application/json' }).end(body);
    if (req.url !== '/api/chat') return send(JSON.stringify({ models: [{ name: model, model }] }));
    if (working < MOCK_AT_ONCE) work(() => send(answer));
    else waiting.push(() => send(answer));
    mockMost = Math.max(mockMost, working + waiting.length);
  });
});

/* ---------- memory: the app and every Tesseract process on this machine (an upper bound) ---------- */
let memoryMost = { app: 0, ocr: 0, both: 0, ocrProcesses: 0, ocrEach: 0 };
function watchMemory(pid) {
  const script = `while ($true) { $a=(Get-Process -Id ${pid} -ErrorAction SilentlyContinue).WorkingSet64; $t=@(Get-Process tesseract -ErrorAction SilentlyContinue); $s=($t | Measure-Object WorkingSet64 -Sum).Sum; "$([int64]$a) $([int64]$s) $($t.Count)"; Start-Sleep -Milliseconds 1000 }`;
  const child = spawn('powershell.exe', ['-NoProfile', '-Command', script]);
  child.stdout.on('data', (chunk) => {
    for (const line of chunk.toString().split(/\r?\n/).filter(Boolean)) {
      const [app, ocr, n] = line.split(' ').map(Number);
      if (!(app > 0)) continue;
      const mb = (b) => Math.round(b / 1048576);
      memoryMost = {
        app: Math.max(memoryMost.app, mb(app)), ocr: Math.max(memoryMost.ocr, mb(ocr)), both: Math.max(memoryMost.both, mb(app + ocr)),
        ocrProcesses: Math.max(memoryMost.ocrProcesses, n), ocrEach: n ? Math.max(memoryMost.ocrEach, mb(ocr / n)) : memoryMost.ocrEach,
      };
    }
  });
  return child;
}

async function api(method, path, body) {
  const form = body instanceof FormData;
  const res = await fetch(base + path, {
    method, headers: { ...headers, ...(body && !form ? { 'Content-Type': 'application/json' } : {}) },
    body: body ? (form ? body : JSON.stringify(body)) : undefined,
  });
  if (!res.ok) throw new Error(`${method} ${path.replace(/[0-9a-f-]{36}/, '{id}')}: HTTP ${res.status}`);
  return res.json();
}

let app; let memory; let started = false; let failed = 1;
try {
  if (execFileSync('docker', ['ps', '-aq', '--filter', `name=^/${container}$`], { encoding: 'utf8' }).trim()) throw new Error(`${container} exists already`);
  execFileSync('docker', ['run', '-d', '--name', container, '-e', 'POSTGRES_DB=securedoc', '-e', 'POSTGRES_USER=securedoc',
    '-e', `POSTGRES_PASSWORD=${password}`, '-p', `127.0.0.1:${dbPort}:5432`, 'postgres:16-alpine'], { stdio: 'ignore' });
  started = true;
  for (let i = 0; i < 60 && spawnSync('docker', ['exec', container, 'pg_isready', '-U', 'securedoc'], { stdio: 'ignore' }).status !== 0; i++) await sleep(1000);
  await sleep(3000);
  const url = `jdbc:postgresql://localhost:${dbPort}/securedoc`;
  const init = spawnSync('powershell.exe', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command',
    `. '${join(root, 'scripts', 'lib', 'LexaVaultTools.ps1')}'; Initialize-LexaVaultDatabase -Jar '${jar}' -DbUrl '${url}' -DbUser 'securedoc' -DbPassword $env:LOAD_DB_PASSWORD -StateDir '${join(run, 'install')}'`],
    { env: { ...process.env, LOAD_DB_PASSWORD: password }, encoding: 'utf8' });
  if (init.status !== 0) throw new Error('the install steps failed');

  const m2 = join(process.env.USERPROFILE, '.m2', 'repository');
  const logging = join(m2, 'commons-logging', 'commons-logging');
  const version = readdirSync(logging).find((v) => /^\d/.test(v) && readdirSync(join(logging, v)).some((f) => f.endsWith('.jar')));
  const cp = [...['pdfbox', 'fontbox', 'pdfbox-io'].map((a) => join(m2, 'org', 'apache', 'pdfbox', a, '3.0.2', `${a}-3.0.2.jar`)),
    join(logging, version, `commons-logging-${version}.jar`)].join(';');
  const pdf = {};
  for (const kind of ['typed', 'scanned']) {
    const file = join(run, `${kind}.pdf`);
    execFileSync('java', ['-cp', cp, join(root, 'test-tools', 'two-servers', 'MakeCasePdf.java'), file, kind, '1']);
    pdf[kind] = readFileSync(file);
  }

  await new Promise((r) => mock.listen(aiPort, '127.0.0.1', r));
  app = spawn('java', [`-Xmx${heap}`, '-jar', jar, '--spring.profiles.active=local', `--securedoc.storage.local.root-path=${join(run, 'storage')}`], {
    cwd: run, stdio: ['ignore', openSync(join(run, 'app.log'), 'w'), openSync(join(run, 'app.err'), 'w')],
    env: { ...process.env, SPRING_DATASOURCE_URL: url, SPRING_DATASOURCE_USERNAME: 'securedoc', SPRING_DATASOURCE_PASSWORD: password,
      SERVER_PORT: String(appPort), SECUREDOC_NODE_ID: 'node-load', SECUREDOC_AI_PROVIDER: 'ollama',
      SECUREDOC_AI_OLLAMA_BASE_URL: `http://localhost:${aiPort}`, SECUREDOC_AI_VISION_BASE_URL: `http://localhost:${aiPort}`,
      SECUREDOC_AI_MODEL: model, SECUREDOC_AI_OLLAMA_PRELOAD_ENABLED: 'false', SECUREDOC_AI_REQUESTS_AT_ONCE: '',
      SECUREDOC_AI_LEARN_UP_EVERY_MS: '8000', SECUREDOC_WATCHED_FOLDER_ENABLED: 'false',
      SECUREDOC_WATCHED_FOLDER_SCHEDULING_ENABLED: 'false', SECUREDOC_PROCESSING_PIPELINE_MODE: 'staged',
      SECUREDOC_PROCESSING_EXPORT_MODE: 'job', SECUREDOC_DELIVERY_LOCAL_OUTPUT_PATH: join(run, 'export'),
      SECUREDOC_PRODUCT_TELEMETRY_OUTPUT_PATH: join(run, 'telemetry.jsonl') },
  });
  let up = false;
  for (let i = 0; i < 90 && !up; i++) {
    await sleep(2000);
    if (app.exitCode !== null) throw new Error('the app stopped while starting');
    try { up = (await (await fetch(`${base}/actuator/health`)).json()).status === 'UP'; } catch { /* not yet */ }
  }
  if (!up) throw new Error('the app did not start');
  memory = watchMemory(app.pid);

  const cases = Math.ceil(documents / PER_CASE);
  const suffix = String(Math.floor(100000 + Math.random() * 900000));
  const begin = Date.now();
  let next = 0;
  let sent = 0;
  let sendingDone = false;
  const errors = [];
  const senders = Array.from({ length: 4 }, async () => {
    while (next < cases) {
      const n = next++;
      try {
        const { packageId } = await api('POST', '/api/v1/packages', { packageType: 'MANUAL_UPLOAD', metadata: { source: 'load-run' },
          businessIdentifiers: { contract_number: `88${String(n).padStart(4, '0')}${suffix}`, member_number: `9${String(n).padStart(4, '0')}${suffix}`, service_entity: '001' } });
        for (let d = 0; d < PER_CASE; d++) {
          const form = new FormData();
          const kind = d % 2 ? 'scanned' : 'typed';
          form.append('file', new Blob([pdf[kind]], { type: 'application/pdf' }), `${kind}-${d}.pdf`);
          await api('POST', `/api/v1/packages/${packageId}/documents`, form);
          sent++;
        }
        await api('POST', `/api/v1/packages/${packageId}/submit`);
      } catch (e) {
        errors.push(e.message);
      }
    }
  });
  Promise.all(senders).then(() => { sendingDone = true; });

  const most = {};
  const samples = [];
  const etas = [];
  let highest = {};
  let aiMost = 0;
  let quiet = 0;
  let exportedAt = null;
  while (Date.now() - begin < 3 * 3600_000 && quiet < 12) {
    await sleep(2000);
    let state;
    try { state = await api('GET', '/api/v1/platform-admin/capacity'); } catch (e) { errors.push(e.message); continue; }
    for (const s of state.stages) {
      most[s.stage] = Math.max(most[s.stage] ?? 0, s.current);
      highest[s.stage] = s.highest;
    }
    const ai = state.aiServers[0]?.requestsAtOnce ?? 0;
    aiMost = Math.max(aiMost, ai);
    samples.push({ s: Math.round((Date.now() - begin) / 1000), queue: state.queueLength, ai, slowdown: state.intakeSlowdown,
      workers: Object.fromEntries(state.stages.map((s) => [s.stage, s.current])) });
    if (sendingDone) {
      const q = await api('GET', '/api/v1/platform-admin/queue').catch((e) => { errors.push(e.message); return null; });
      if (q) etas.push({ at: Date.now(), eta: q.etaSeconds ?? null, left: q.stages.map((s) => `${s.stage} ${s.waiting + s.running}+${s.toCome} ${s.etaSeconds ?? '?'}`).join(', ') });
    }
    if (samples.length % 15 === 0) console.log(JSON.stringify(samples.at(-1)), `sent ${sent}`, JSON.stringify(memoryMost));
    if (sendingDone && state.queueLength === 0 && state.stages.every((s) => s.busy === 0)) {
      exportedAt ??= Date.now();
      quiet++;
    } else {
      quiet = 0;
      exportedAt = null;
    }
  }
  const last = samples.at(-1);
  const statuses = sql("select status || ' ' || count(*) from document_packages group by status").split('\n');
  const doubles = sql('select count(*) from (select 1 from pipeline_jobs where stage in (\'TEXT\', \'FIND\', \'REDACT\') group by document_id, stage, page_from having count(*) > 1) x');
  // The database says when the last job ended; its clock and this machine's are the same clock.
  const ended = Number(sql("select extract(epoch from max(updated_at)) from pipeline_jobs where status = 'DONE'")) * 1000;
  const draining = etas.filter((e) => e.at < ended);
  const drainStart = draining[0]?.at ?? ended;
  const etaAt = (part) => {
    const e = draining.find((x) => x.at >= drainStart + (ended - drainStart) * part);
    if (!e || e.eta === null) return { part, ok: false, text: 'no ETA' };
    const real = (ended - e.at) / 1000;
    const off = (e.eta - real) / real;
    return { part, ok: Math.abs(off) <= 0.2, text: `said ${e.eta} s, was ${Math.round(real)} s (${off >= 0 ? '+' : ''}${Math.round(off * 100)}%)` };
  };
  const etaRows = [0, 0.1, 0.25, 0.5, 0.75].map(etaAt);
  for (const r of etaRows) console.log(`ETA at ${r.part * 100}% of the drain: ${r.text}`);
  const checks = [
    ['the ETA is within 20% at a quarter and at half of the drain', etaRows[2].ok && etaRows[3].ok, `${etaRows[2].text}; ${etaRows[3].text}`],
    ['no request failed', errors.length === 0, errors.slice(0, 3).join('; ') || 'none'],
    ['every case exported', statuses.length === 1 && statuses[0] === `COMPLETED ${cases}`, statuses.join(', ')],
    ['no job twice', doubles === '0', doubles],
    ['Text workers rose to their highest', most.TEXT === highest.TEXT, `${most.TEXT} of ${highest.TEXT}`],
    ['every stage came back to its lowest', Object.values(last.workers).every((n) => n === 1), JSON.stringify(last.workers)],
    ['the learned AI limit stayed within one of what the AI server takes', aiMost <= MOCK_AT_ONCE + 1 && aiMost > 1, `most ${aiMost}, at the end ${last.ai}, AI server takes ${MOCK_AT_ONCE}`],
    ['memory stayed under the limit', memoryMost.both > 0 && memoryMost.both < memoryLimitMb, `${memoryMost.both} MB of ${memoryLimitMb} MB`],
  ];
  for (const [name, ok, detail] of checks) console.log(ok ? 'PASS' : 'FAIL', name, '-', detail);
  const summary = { documents, cases, seconds: Math.round(((exportedAt ?? Date.now()) - begin) / 1000), most, highest, aiMost, mockMost, memoryMost, checks };
  writeFileSync(join(run, 'summary.json'), JSON.stringify({ ...summary, samples, etas, etaRows, ended }, null, 1));
  console.log(JSON.stringify(summary));
  console.log('Run folder:', run);
  failed = checks.filter((c) => !c[1]).length;
} catch (e) {
  console.error('STOPPED:', e.message, '- run folder', run);
} finally {
  memory?.kill();
  if (app && app.exitCode === null) app.kill('SIGKILL');
  mock.close();
  mock.closeAllConnections();
  if (started) spawnSync('docker', ['rm', '-f', container], { stdio: 'ignore' });
}
process.exit(failed ? 1 : 0);
