#!/usr/bin/env python3
"""Dependency-free HTTP destination for LexaVault derived-document final regression.
Supports HEAD health checks and POST uploads with Content-Length or chunked transfer encoding.
Writes received PDFs and an events JSONL file beneath the chosen output directory.
"""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import argparse, json, time, uuid

class Handler(BaseHTTPRequestHandler):
    server_version = "LexaVaultDerivedMock/1.0"

    def log_message(self, fmt, *args):
        print("[%s] %s" % (self.log_date_time_string(), fmt % args))

    def _read_body(self):
        te = (self.headers.get('Transfer-Encoding') or '').lower()
        if 'chunked' in te:
            data = bytearray()
            while True:
                line = self.rfile.readline().strip()
                if not line:
                    continue
                size = int(line.split(b';',1)[0], 16)
                if size == 0:
                    while True:
                        trailer = self.rfile.readline()
                        if trailer in (b'\r\n', b'\n', b''):
                            break
                    break
                data += self.rfile.read(size)
                self.rfile.read(2)
            return bytes(data)
        length = int(self.headers.get('Content-Length') or 0)
        return self.rfile.read(length) if length else b''

    def _event(self, payload):
        payload['atEpochMs'] = int(time.time()*1000)
        with (self.server.output_dir / 'events.jsonl').open('a', encoding='utf-8') as f:
            f.write(json.dumps(payload, sort_keys=True) + '\n')

    def do_HEAD(self):
        self._event({'method':'HEAD','path':self.path})
        self.send_response(200); self.end_headers()

    def do_GET(self):
        self._event({'method':'GET','path':self.path})
        body = json.dumps({'status':'ok'}).encode()
        self.send_response(200); self.send_header('Content-Type','application/json'); self.send_header('Content-Length',str(len(body))); self.end_headers(); self.wfile.write(body)

    def do_POST(self):
        body = self._read_body()
        source_id = self.headers.get('X-Source-Derived-Document-Id') or self.headers.get('Idempotency-Key') or str(uuid.uuid4())
        safe = ''.join(c if c.isalnum() or c in '._-' else '_' for c in source_id)
        target = self.server.output_dir / f'{safe}.pdf'
        target.write_bytes(body)
        receipt = 'receipt-' + safe
        self._event({
            'method':'POST','path':self.path,'bytes':len(body),'file':target.name,
            'idempotencyKey':self.headers.get('Idempotency-Key'),
            'sourceDerivedDocumentId':self.headers.get('X-Source-Derived-Document-Id'),
            'routingMarker':self.headers.get('X-Routing-Marker'),
            'routingPages':self.headers.get('X-LexaVault-Routing-Pages'),
            'destinationLabel':self.headers.get('X-LexaVault-Destination-Label'),
        })
        response = json.dumps({'receiptId': receipt}).encode()
        self.send_response(200); self.send_header('Content-Type','application/json'); self.send_header('Content-Length',str(len(response))); self.end_headers(); self.wfile.write(response)

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--port', type=int, default=19090)
    parser.add_argument('--output', default='derived-routing-mock-output')
    args = parser.parse_args()
    output = Path(args.output).resolve(); output.mkdir(parents=True, exist_ok=True)
    server = ThreadingHTTPServer(('127.0.0.1', args.port), Handler); server.output_dir = output
    print(f'Listening on http://127.0.0.1:{args.port}; output={output}')
    try: server.serve_forever()
    except KeyboardInterrupt: pass
    finally: server.server_close()
