#!/usr/bin/env python3
"""Host-side fixture for the emulator live check (tests/android ... LiveRunner section).

Starts (1) a stub OpenAI-compatible model server on loopback and (2) the real VictorBots PC runner on
0.0.0.0 with TLS, a fresh self-signed certificate and a random token, forwarding /v1/* to the stub.
Prints one JSON line {port, pin, token} then runs until killed. The STUB is a fixture, not a real model.
"""
import hashlib, http.server, json, os, secrets, subprocess, sys, tempfile, threading, time
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "pc-runner"))
import victorbots_runner as vr

class Stub(http.server.BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def do_GET(self):
        if self.path == "/v1/models":
            self.reply(200, {"data": [{"id": "stub-model"}]})
        elif self.path == "/api/version":
            self.reply(404, {})
        else:
            self.reply(404, {})
    def reply(self, code, obj):
        b = json.dumps(obj).encode(); self.send_response(code); self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)
    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0)); body = json.loads(self.rfile.read(n) or b"{}")
        if self.path != "/v1/chat/completions": return self.reply(404, {})
        if body.get("stream"):
            self.send_response(200); self.send_header("Content-Type", "text/event-stream"); self.end_headers()
            for piece in ["stub ", "stream ", "ok"]:
                time.sleep(0.15)
                self.wfile.write(("data: " + json.dumps({"model": "stub-model-served", "choices": [{"delta": {"content": piece}}]}) + "\n\n").encode()); self.wfile.flush()
            self.wfile.write(("data: " + json.dumps({"choices": [], "usage": {"prompt_tokens": 17, "completion_tokens": 3, "total_tokens": 20}}) + "\n\n").encode())
            self.wfile.write(b"data: [DONE]\n\n")
        else:
            self.reply(200, {"model": "stub-model-served", "choices": [{"message": {"content": "stub reply"}}], "usage": {"prompt_tokens": 12, "completion_tokens": 2, "total_tokens": 14}})

def main():
    d = tempfile.mkdtemp()
    up = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Stub); threading.Thread(target=up.serve_forever, daemon=True).start()
    subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", d + "/k.pem", "-out", d + "/c.pem", "-days", "2", "-subj", "/CN=pc", "-addext", "subjectAltName=IP:10.0.2.2,DNS:localhost"], check=True, capture_output=True)
    der = subprocess.run(["openssl", "x509", "-in", d + "/c.pem", "-outform", "DER"], check=True, capture_output=True).stdout
    pin = hashlib.sha256(der).hexdigest().upper()
    token = secrets.token_urlsafe(24)
    ws = os.path.join(d, "ws"); os.makedirs(ws)
    open(os.path.join(ws, "hello.txt"), "w").write("live-fixture-file\n")
    port = 28443
    cfg = {"listen": {"host": "0.0.0.0", "port": port, "cert": d + "/c.pem", "key": d + "/k.pem"}, "token_sha256": hashlib.sha256(token.encode()).hexdigest(), "state_dir": d + "/state",
           "inference_upstream": "http://127.0.0.1:%d" % up.server_address[1],
           "workspaces": {"main": {"path": ws, "mode": "rw"}},
           "commands": {"slow": {"argv": ["sh", "-c", "echo begin; sleep 60"], "requires_approval": False},
                        "guarded": {"argv": ["sh", "-c", "echo guarded-ran"], "requires_approval": True}},
           "limits": {"approval_ttl_s": 120}}
    r = vr.Runner(vr.Config(cfg)); vr.Handler.runner = r
    import ssl
    srv = vr.ThreadingHTTPServer(("0.0.0.0", port), vr.Handler); srv.daemon_threads = True
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER); ctx.load_cert_chain(d + "/c.pem", d + "/k.pem"); srv.socket = ctx.wrap_socket(srv.socket, server_side=True)
    threading.Thread(target=r.scheduler_loop, daemon=True).start()
    print(json.dumps({"port": port, "pin": pin, "token": token}), flush=True)
    srv.serve_forever()

main()
