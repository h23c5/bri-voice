# -*- coding: utf-8 -*-
"""A stand-in for an OpenAI-compatible endpoint, so the AI path can be tested without a key."""
import json, http.server, socketserver, sys

CURVES = {"curves": {
    "DYN": [[0, 88], [960, 52], [1920, 104], [2880, 60], [4000, 96]],
    "PIT": [[0, 0], [480, 35], [960, -25], [1920, 0], [2880, -40]],
    "VIB": [[0, 20], [1440, 70], [2880, 35]],
    "BRE": [[0, 55], [2000, 80]]}}

class H(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        n = int(self.headers.get('Content-Length', 0))
        raw = self.rfile.read(n).decode('utf-8')
        try:
            req = json.loads(raw)
        except Exception:
            req = {}
        user = ''
        for m in req.get('messages', []):
            if m.get('role') == 'user': user = m.get('content', '')
        print('REQ model=%s key=%s userLen=%d' % (req.get('model'), bool(self.headers.get('Authorization')), len(user)), flush=True)
        body = json.dumps({'choices': [{'message': {'role': 'assistant',
                'content': 'Here you go:\n```json\n' + json.dumps(CURVES) + '\n```'}}]}).encode('utf-8')
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)
    def log_message(self, *a): pass

socketserver.TCPServer.allow_reuse_address = True
srv = socketserver.TCPServer(('0.0.0.0', 8799), H)
print('stub listening on 8799', flush=True)
srv.serve_forever()