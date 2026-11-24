/**
 * Task 0035: a mock Ollama for the export end-to-end run. It finds nothing on any page, so each case finishes without
 * a review and goes straight to its export job. Listens on 127.0.0.1 only; stopped by the returned teardown.
 */
import * as http from 'node:http';
import { E2E_PORTS } from '../e2ePorts';

export default async function globalSetup(): Promise<() => Promise<void>> {
  const port = E2E_PORTS.exportOllama;
  const model = 'qwen3.5:9b';
  const chat = JSON.stringify({ message: { content: JSON.stringify({ findings: [] }) }, done_reason: 'stop', done: true });
  const server = http.createServer((req, res) => {
    req.resume();
    req.on('end', () => {
      const url = (req.url || '').split('?')[0];
      const send = (status: number, body: string) => {
        res.writeHead(status, { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) });
        res.end(body);
      };
      if (url === '/api/tags') send(200, JSON.stringify({ models: [{ name: model, model }] }));
      else if (url === '/api/chat') send(200, chat);
      else if (url === '/api/version') send(200, '{"version":"0.0.0-mock"}');
      else send(404, '{}');
    });
  });
  await new Promise<void>((resolve, reject) => { server.once('error', reject); server.listen(port, '127.0.0.1', resolve); });
  return async () => { await new Promise<void>((resolve) => server.close(() => resolve())); };
}
