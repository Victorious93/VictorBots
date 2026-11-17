#!/usr/bin/env python3
"""VictorBots PC runner (protocol 1).

A small authenticated HTTPS service that gives the VictorBots Android app:
  * bounded, logged, cancellable jobs in explicitly configured workspaces,
  * an OpenAI-compatible inference pass-through to a loopback model server (optional),
  * owner-defined schedules (routines) that keep running when the phone is offline,
  * a read-only bridge to selected MCP servers (optional).

Design rules: stdlib only; every request needs the bearer token; workspaces, tools and commands come
from the config file only (the phone cannot ask for arbitrary commands); output is redacted and size
bounded; the phone's "approve" is the only way an approval-required job proceeds; nothing is claimed
completed unless the process exited 0.
"""
import argparse, base64, fnmatch, glob, hashlib, hmac, http.client, json, os, re, shutil, signal, sqlite3, ssl, subprocess, sys, threading, time, uuid
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs, unquote

try:
    from zoneinfo import ZoneInfo
except ImportError:  # pragma: no cover
    ZoneInfo = None

VERSION = "0.1.0"
PROTOCOL = 1
SECRET_FILE_PATTERNS = [".env", ".env.*", "*.pem", "*.key", "*.p12", "*.pfx", "*.jks", "*.keystore", "id_rsa", "id_ed25519", "id_ecdsa", "*.kdbx", "credentials*", "secrets*", ".netrc", ".npmrc", ".pypirc", "local.properties"]
REDACT = [
    (re.compile(r"(?i)(authorization:?\s*(?:(?:bearer|basic)\s+)?|bearer\s+)[A-Za-z0-9._~+/=-]{6,}"), r"\1[redacted]"),
    (re.compile(r"(?i)(api[_-]?key|token|secret|password|passwd)\s*[=:]\s*[^\s\"',;]{4,}"), r"\1=[redacted]"),
    (re.compile(r"\b(sk|pk|rk|key|tok|ghp|gho|ghs|xox[bap])[-_][A-Za-z0-9_-]{8,}"), "[redacted]"),
    (re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----.*?-----END [A-Z ]*PRIVATE KEY-----", re.S), "[redacted private key]"),
]
TERMINAL = {"completed", "failed", "cancelled"}


def redact(s):
    for rx, rep in REDACT:
        s = rx.sub(rep, s)
    return s


def now():
    return time.time()


class Config:
    def __init__(self, d):
        self.raw = d
        listen = d.get("listen", {})
        self.host = listen.get("host", "127.0.0.1")
        self.port = int(listen.get("port", 8443))
        self.cert = listen.get("cert")
        self.key = listen.get("key")
        self.token_sha256 = d.get("token_sha256", "").lower()
        if not re.fullmatch(r"[0-9a-f]{64}", self.token_sha256):
            raise SystemExit("config: token_sha256 must be the SHA-256 (hex) of the bearer token")
        self.state_dir = d.get("state_dir", "./state")
        self.workspaces = {}
        for wid, w in d.get("workspaces", {}).items():
            if not re.fullmatch(r"[A-Za-z0-9_-]{1,32}", wid):
                raise SystemExit("config: bad workspace id %r" % wid)
            p = os.path.realpath(w["path"])
            if not os.path.isdir(p):
                raise SystemExit("config: workspace %s path does not exist: %s" % (wid, p))
            mode = w.get("mode", "ro")
            if mode not in ("ro", "rw"):
                raise SystemExit("config: workspace mode must be ro or rw")
            self.workspaces[wid] = {"path": p, "mode": mode, "isolation": w.get("isolation", "lock")}
        self.commands = d.get("commands", {})
        for cid, c in self.commands.items():
            if not re.fullmatch(r"[A-Za-z0-9_.-]{1,40}", cid) or not isinstance(c.get("argv"), list) or not c["argv"]:
                raise SystemExit("config: command %r needs an argv list" % cid)
        self.connectors = d.get("connectors", {})
        self.limits = {"max_job_seconds": 3600, "max_output_bytes": 2 * 1024 * 1024, "max_artifact_bytes": 64 * 1024 * 1024,
                       "approval_ttl_s": 600, "max_running": 2, "retention_jobs": 200, "max_event_chars": 4000}
        self.limits.update(d.get("limits", {}))
        up = d.get("inference_upstream")
        self.upstream = None
        if up:
            u = urlparse(up)
            if u.scheme not in ("http", "https") or not u.hostname:
                raise SystemExit("config: inference_upstream must be an http(s) URL")
            if u.hostname not in ("127.0.0.1", "localhost", "::1") and not d.get("allow_remote_upstream"):
                raise SystemExit("config: inference_upstream must be loopback (set allow_remote_upstream to override deliberately)")
            self.upstream = u


class Store:
    def __init__(self, state_dir):
        os.makedirs(state_dir, mode=0o700, exist_ok=True)
        self.dir = state_dir
        self.db = sqlite3.connect(os.path.join(state_dir, "runner.db"), check_same_thread=False, isolation_level=None)
        self.lock = threading.RLock()
        self.db.executescript("""
        PRAGMA journal_mode=WAL;
        CREATE TABLE IF NOT EXISTS jobs(id TEXT PRIMARY KEY, request_id TEXT UNIQUE NOT NULL, title TEXT, workspace TEXT, tool TEXT, args TEXT,
          state TEXT NOT NULL, created REAL, started REAL, finished REAL, exit_code INTEGER, error TEXT DEFAULT '', result TEXT DEFAULT '{}',
          approval_deadline REAL, pgid INTEGER, last_seq INTEGER DEFAULT 0, origin TEXT DEFAULT 'api');
        CREATE TABLE IF NOT EXISTS events(job TEXT, seq INTEGER, ts REAL, stream TEXT, text TEXT, PRIMARY KEY(job,seq));
        CREATE TABLE IF NOT EXISTS artifacts(job TEXT, name TEXT, kind TEXT, size INTEGER, sha256 TEXT, path TEXT, mime TEXT, PRIMARY KEY(job,name));
        CREATE TABLE IF NOT EXISTS routines(uid TEXT PRIMARY KEY, def TEXT NOT NULL, paused INTEGER DEFAULT 0, next_run REAL, updated REAL);
        CREATE TABLE IF NOT EXISTS routine_runs(uid TEXT, scheduled REAL, started REAL, status TEXT, job TEXT, note TEXT DEFAULT '', PRIMARY KEY(uid,scheduled));
        """)

    def q(self, sql, *a):
        with self.lock:
            cur = self.db.execute(sql, a)
            cols = [c[0] for c in cur.description] if cur.description else []
            return [dict(zip(cols, r)) for r in cur.fetchall()]

    def x(self, sql, *a):
        with self.lock:
            return self.db.execute(sql, a).rowcount


def safe_join(root, rel):
    """Resolve rel inside root, refusing traversal and symlink escapes."""
    if rel is None or rel == "":
        rel = "."
    if "\x00" in rel or os.path.isabs(rel):
        raise ValueError("path must be relative to the workspace")
    full = os.path.realpath(os.path.join(root, rel))
    if full != root and not full.startswith(root + os.sep):
        raise ValueError("path escapes the workspace")
    return full


def is_secret_path(path):
    base = os.path.basename(path)
    return any(fnmatch.fnmatch(base.lower(), p.lower()) for p in SECRET_FILE_PATTERNS) or "/.ssh/" in path or "/.gnupg/" in path


class Job:
    """In-memory handle for a running process."""
    def __init__(self):
        self.proc = None
        self.cancel = threading.Event()


class Runner:
    def __init__(self, cfg):
        self.cfg = cfg
        self.store = Store(cfg.state_dir)
        self.running = {}  # job id -> Job
        self.write_locks = set()  # workspace ids with a running writer
        self.mx = threading.RLock()
        self.stop = threading.Event()
        self.connectors = {}
        self.recover()

    # ----- persistence helpers -----
    def recover(self):
        for j in self.store.q("SELECT id,pgid FROM jobs WHERE state IN ('running','awaiting_approval')"):
            if j["pgid"]:
                try:
                    os.killpg(j["pgid"], signal.SIGKILL)
                except (ProcessLookupError, PermissionError):
                    pass
            self.finish(j["id"], "failed", error="interrupted by a runner restart; the process was terminated", exit_code=None)
        # queued jobs survive restarts and run when the scheduler starts

    def event(self, jid, stream, text):
        text = redact(text)
        if len(text) > self.cfg.limits["max_event_chars"]:
            text = text[: self.cfg.limits["max_event_chars"]] + "…[cut]"
        with self.store.lock:
            r = self.store.q("SELECT last_seq FROM jobs WHERE id=?", jid)
            if not r:
                return
            seq = r[0]["last_seq"] + 1
            self.store.x("UPDATE jobs SET last_seq=? WHERE id=?", seq, jid)
            self.store.x("INSERT INTO events(job,seq,ts,stream,text) VALUES(?,?,?,?,?)", jid, seq, now(), stream, text)
            keep = self.cfg.limits["max_output_bytes"] // 80
            self.store.x("DELETE FROM events WHERE job=? AND seq<=?", jid, seq - max(keep, 500))

    def finish(self, jid, state, error="", exit_code=None, result=None):
        with self.store.lock:
            cur = self.store.q("SELECT state FROM jobs WHERE id=?", jid)
            if not cur or cur[0]["state"] in TERMINAL:
                return  # terminal states never change
            self.store.x("UPDATE jobs SET state=?,finished=?,error=?,exit_code=?,result=?,pgid=NULL WHERE id=?",
                         state, now(), redact(error)[:300], exit_code, json.dumps(result or {}), jid)

    def job_view(self, jid):
        r = self.store.q("SELECT * FROM jobs WHERE id=?", jid)
        if not r:
            return None
        j = r[0]
        arts = self.store.q("SELECT name,kind,size,sha256,mime FROM artifacts WHERE job=?", jid)
        return {"id": j["id"], "request_id": j["request_id"], "title": j["title"], "workspace": j["workspace"], "tool": j["tool"], "state": j["state"],
                "created": j["created"], "started": j["started"], "finished": j["finished"], "exit_code": j["exit_code"], "error": j["error"],
                "result": json.loads(j["result"] or "{}"), "artifacts": arts, "last_seq": j["last_seq"], "origin": j["origin"]}

    # ----- tool catalogue -----
    def catalogue(self):
        tools = [
            {"name": "repo.list", "risk": "read", "requires_approval": False, "args": {"path": "string, relative, default '.'"}},
            {"name": "repo.read", "risk": "read", "requires_approval": False, "args": {"path": "string", "max_bytes": "int <= 262144"}},
            {"name": "repo.search", "risk": "read", "requires_approval": False, "args": {"pattern": "fixed string", "path": "string"}},
            {"name": "git.status", "risk": "read", "requires_approval": False, "args": {}},
            {"name": "git.diff", "risk": "read", "requires_approval": False, "args": {"staged": "bool"}},
        ]
        for cid, c in self.cfg.commands.items():
            tools.append({"name": "cmd." + cid, "risk": "write" if c.get("workspace_write") else "exec", "requires_approval": bool(c.get("requires_approval", True)),
                          "description": c.get("description", ""), "args": {}})
        return tools

    def capabilities(self):
        return {"protocol": PROTOCOL, "runner": "victorbots-runner", "version": VERSION,
                "workspaces": [{"id": k, "mode": v["mode"], "isolation": v["isolation"]} for k, v in self.cfg.workspaces.items()],
                "tools": self.catalogue(),
                "connectors": [{"id": k, "type": v.get("type"), "tools": v.get("allow_tools", [])} for k, v in self.cfg.connectors.items()],
                "inference": bool(self.cfg.upstream), "routines": True,
                "limits": {k: self.cfg.limits[k] for k in ("max_job_seconds", "max_output_bytes", "max_artifact_bytes", "approval_ttl_s", "max_running")}}

    # ----- job lifecycle -----
    def submit(self, body, origin="api"):
        rid = body.get("request_id")
        if not isinstance(rid, str) or not re.fullmatch(r"[A-Za-z0-9:_.-]{8,80}", rid):
            raise ValueError("request_id must be 8-80 characters of [A-Za-z0-9:_.-]")
        ex = self.store.q("SELECT id FROM jobs WHERE request_id=?", rid)
        if ex:
            v = self.job_view(ex[0]["id"])
            v["duplicate"] = True
            return v
        ws, tool = body.get("workspace"), body.get("tool")
        args = body.get("args") or {}
        if ws not in self.cfg.workspaces:
            raise ValueError("unknown workspace")
        if not isinstance(args, dict) or len(json.dumps(args)) > 16000:
            raise ValueError("bad args")
        tools = {t["name"]: t for t in self.catalogue()}
        if tool not in tools:
            raise ValueError("unknown tool")
        spec = tools[tool]
        wsc = self.cfg.workspaces[ws]
        if spec["risk"] in ("write",) and wsc["mode"] != "rw":
            raise ValueError("workspace is read-only")
        self.validate_args(tool, args)
        needs = bool(spec["requires_approval"])
        jid = uuid.uuid4().hex[:24]
        state = "awaiting_approval" if needs else "queued"
        deadline = now() + self.cfg.limits["approval_ttl_s"] if needs else None
        self.store.x("INSERT INTO jobs(id,request_id,title,workspace,tool,args,state,created,approval_deadline,origin) VALUES(?,?,?,?,?,?,?,?,?,?)",
                     jid, rid, str(body.get("title", ""))[:120], ws, tool, json.dumps(args), state, now(), deadline, origin)
        self.event(jid, "stdout", "job accepted: %s in workspace %s%s" % (tool, ws, " (waiting for approval)" if needs else ""))
        self.prune()
        return self.job_view(jid)

    def validate_args(self, tool, args):
        if tool in ("repo.list", "repo.read", "repo.search"):
            if "path" in args and not isinstance(args["path"], str):
                raise ValueError("path must be a string")
        if tool == "repo.read" and not isinstance(args.get("path"), str):
            raise ValueError("path is required")
        if tool == "repo.search" and not (isinstance(args.get("pattern"), str) and 1 <= len(args["pattern"]) <= 200):
            raise ValueError("pattern is required (1-200 chars)")
        if tool.startswith("cmd.") and args:
            raise ValueError("commands take no arguments from the phone")

    def prune(self):
        keep = self.cfg.limits["retention_jobs"]
        old = self.store.q("SELECT id FROM jobs WHERE state IN ('completed','failed','cancelled') ORDER BY created DESC LIMIT -1 OFFSET ?", keep)
        for o in old:
            self.store.x("DELETE FROM events WHERE job=?", o["id"])
            for a in self.store.q("SELECT path FROM artifacts WHERE job=?", o["id"]):
                if a["path"] and a["path"].startswith(self.store.dir):
                    try: os.remove(a["path"])
                    except OSError: pass
            self.store.x("DELETE FROM artifacts WHERE job=?", o["id"])
            self.store.x("DELETE FROM jobs WHERE id=?", o["id"])

    def approve(self, jid, allow):
        with self.store.lock:
            j = self.store.q("SELECT state,approval_deadline FROM jobs WHERE id=?", jid)
            if not j:
                raise KeyError("no such job")
            j = j[0]
            if j["state"] != "awaiting_approval":
                raise ValueError("job is not awaiting approval (it is %s)" % j["state"])
            if j["approval_deadline"] and now() > j["approval_deadline"]:
                self.finish(jid, "cancelled", error="approval expired")
                raise ValueError("approval expired")
            if allow:
                self.store.x("UPDATE jobs SET state='queued',approval_deadline=NULL WHERE id=?", jid)
                self.event(jid, "stdout", "approved by the owner")
            else:
                self.finish(jid, "cancelled", error="denied by the owner")
                self.event(jid, "stdout", "denied by the owner")
        return self.job_view(jid)

    def cancel(self, jid):
        j = self.store.q("SELECT state FROM jobs WHERE id=?", jid)
        if not j:
            raise KeyError("no such job")
        st = j[0]["state"]
        if st in TERMINAL:
            return self.job_view(jid)
        h = self.running.get(jid)
        if st == "running" and h:
            h.cancel.set()
            self.kill(h)
            self.event(jid, "stdout", "cancel requested by the owner")
            for _ in range(60):
                time.sleep(0.1)
                if self.store.q("SELECT state FROM jobs WHERE id=?", jid)[0]["state"] in TERMINAL:
                    break
        else:
            self.finish(jid, "cancelled", error="cancelled before it started")
            self.event(jid, "stdout", "cancelled before it started")
        return self.job_view(jid)

    def kill(self, h):
        p = h.proc
        if p and p.poll() is None:
            try:
                os.killpg(os.getpgid(p.pid), signal.SIGTERM)
            except (ProcessLookupError, PermissionError):
                pass
            def hard():
                time.sleep(5)
                if p.poll() is None:
                    try: os.killpg(os.getpgid(p.pid), signal.SIGKILL)
                    except (ProcessLookupError, PermissionError): pass
            threading.Thread(target=hard, daemon=True).start()

    # ----- scheduler -----
    def scheduler_loop(self):
        while not self.stop.is_set():
            try:
                self.expire_approvals()
                self.start_runnable()
                self.routines_tick()
            except Exception as e:  # never die
                sys.stderr.write("scheduler error: %s\n" % redact(str(e)))
            self.stop.wait(0.25)

    def expire_approvals(self):
        for j in self.store.q("SELECT id FROM jobs WHERE state='awaiting_approval' AND approval_deadline IS NOT NULL AND approval_deadline<?", now()):
            self.finish(j["id"], "cancelled", error="approval expired")
            self.event(j["id"], "stdout", "approval expired; the job was cancelled and never ran")

    def start_runnable(self):
        with self.mx:
            if len(self.running) >= self.cfg.limits["max_running"]:
                return
            for j in self.store.q("SELECT * FROM jobs WHERE state='queued' ORDER BY created"):
                if len(self.running) >= self.cfg.limits["max_running"]:
                    break
                ws = self.cfg.workspaces[j["workspace"]]
                writer = j["tool"].startswith("cmd.") and self.cfg.commands[j["tool"][4:]].get("workspace_write", False)
                if writer and ws["isolation"] != "worktree":
                    if j["workspace"] in self.write_locks:
                        continue  # serialised behind the running writer
                    self.write_locks.add(j["workspace"])
                h = Job()
                self.running[j["id"]] = h
                self.store.x("UPDATE jobs SET state='running',started=? WHERE id=? AND state='queued'", now(), j["id"])
                threading.Thread(target=self.run_job, args=(dict(j), h, writer), daemon=True).start()

    def run_job(self, j, h, writer):
        jid = j["id"]
        wsid = j["workspace"]
        ws = self.cfg.workspaces[wsid]
        workdir = ws["path"]
        wt = None
        try:
            args = json.loads(j["args"] or "{}")
            if writer and ws["isolation"] == "worktree":
                wt = os.path.join(self.store.dir, "worktrees", jid)
                os.makedirs(os.path.dirname(wt), exist_ok=True)
                r = subprocess.run(self.git(ws["path"], "worktree", "add", "--detach", wt, "HEAD"), capture_output=True, text=True, timeout=120)
                if r.returncode != 0:
                    raise RuntimeError("could not create an isolated worktree: " + r.stderr[:200])
                workdir = wt
                self.event(jid, "stdout", "isolated worktree created for this write job")
            tool = j["tool"]
            if tool.startswith("cmd."):
                code = self.run_command(jid, tool[4:], workdir, h)
                if h.cancel.is_set():
                    self.finish(jid, "cancelled", error="cancelled by the owner", exit_code=code)
                elif code == 0:
                    self.collect_artifacts(jid, tool[4:], workdir)
                    self.finish(jid, "completed", exit_code=0)
                elif code == -9999:
                    self.finish(jid, "failed", error="exceeded the time limit", exit_code=None)
                else:
                    self.finish(jid, "failed", error="command exited with status %s" % code, exit_code=code)
            else:
                res = self.run_builtin(jid, tool, workdir, args)
                self.finish(jid, "completed", exit_code=0, result=res)
        except Exception as e:
            if h.cancel.is_set():
                self.finish(jid, "cancelled", error="cancelled by the owner")
            else:
                self.event(jid, "stderr", "error: %s" % e)
                self.finish(jid, "failed", error=str(e))
        finally:
            if wt:
                try:
                    subprocess.run(self.git(ws["path"], "worktree", "remove", "--force", wt), capture_output=True, timeout=120)
                except Exception:
                    shutil.rmtree(wt, ignore_errors=True)
            with self.mx:
                self.running.pop(jid, None)
                self.write_locks.discard(wsid) if writer and ws["isolation"] != "worktree" else None

    @staticmethod
    def git(root, *a):
        # No repo-configured commands (fsmonitor, external diff, textconv, hooks) may run.
        return ["git", "-C", root, "-c", "core.fsmonitor=false", "-c", "core.hooksPath=/dev/null", "-c", "diff.external=", "-c", "protocol.ext.allow=never", *a]

    def git_env(self):
        env = {"PATH": os.environ.get("PATH", "/usr/bin:/bin"), "HOME": "/nonexistent", "GIT_CONFIG_GLOBAL": "/dev/null", "GIT_CONFIG_SYSTEM": "/dev/null", "GIT_TERMINAL_PROMPT": "0", "LC_ALL": "C.UTF-8"}
        return env

    def run_builtin(self, jid, tool, root, args):
        lim = self.cfg.limits
        if tool == "repo.list":
            d = safe_join(root, args.get("path", "."))
            names = sorted(os.listdir(d))[:500]
            out = []
            for n in names:
                p = os.path.join(d, n)
                out.append(("d " if os.path.isdir(p) else "f ") + n)
            self.event(jid, "stdout", "\n".join(out))
            return {"entries": len(out)}
        if tool == "repo.read":
            p = safe_join(root, args["path"])
            if is_secret_path(p):
                raise PermissionError("refused: this looks like a credential/secret file")
            if not os.path.isfile(p):
                raise FileNotFoundError("not a file")
            n = max(1, min(int(args.get("max_bytes", 65536)), 262144))
            with open(p, "rb") as f:
                data = f.read(n + 1)
            text = data[:n].decode("utf-8", "replace")
            for i in range(0, len(text), 3500):
                self.event(jid, "stdout", text[i:i + 3500])
            return {"bytes": min(len(data), n), "truncated": len(data) > n}
        if tool == "repo.search":
            d = safe_join(root, args.get("path", "."))
            pat = args["pattern"]
            hits = 0
            for dp, dn, fn in os.walk(d):
                dn[:] = [x for x in dn if x not in (".git", "node_modules", "build")]
                for f in fn:
                    fp = os.path.join(dp, f)
                    if is_secret_path(fp) or os.path.islink(fp):
                        continue
                    try:
                        with open(fp, "rb") as fh:
                            blob = fh.read(1_000_000)
                    except OSError:
                        continue
                    if b"\x00" in blob[:4096]:
                        continue
                    for ln, line in enumerate(blob.decode("utf-8", "replace").splitlines(), 1):
                        if pat in line:
                            self.event(jid, "stdout", "%s:%d: %s" % (os.path.relpath(fp, root), ln, line[:300]))
                            hits += 1
                            if hits >= 200:
                                return {"matches": hits, "truncated": True}
            return {"matches": hits, "truncated": False}
        if tool in ("git.status", "git.diff"):
            argv = self.git(root, "status", "--porcelain=v1") if tool == "git.status" else self.git(root, "diff", "--no-ext-diff", "--no-textconv", *(["--staged"] if args.get("staged") else []))
            r = subprocess.run(argv, capture_output=True, text=True, timeout=60, env=self.git_env())
            out = r.stdout[: lim["max_output_bytes"]]
            for i in range(0, len(out), 3500):
                self.event(jid, "stdout", out[i:i + 3500])
            if r.returncode != 0:
                raise RuntimeError("git failed: " + r.stderr[:200])
            return {"bytes": len(out)}
        raise ValueError("unknown tool")

    def run_command(self, jid, cid, workdir, h):
        c = self.cfg.commands[cid]
        cwd = safe_join(workdir, c.get("cwd", "."))
        env = {k: v for k, v in os.environ.items() if k in ("PATH", "HOME", "LANG", "LC_ALL", "JAVA_HOME", "ANDROID_HOME", "ANDROID_SDK_ROOT")}
        env.update(c.get("env", {}))
        timeout = min(int(c.get("timeout_s", 600)), self.cfg.limits["max_job_seconds"])
        p = subprocess.Popen(c["argv"], cwd=cwd, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL, start_new_session=True, text=True, errors="replace")
        h.proc = p
        self.store.x("UPDATE jobs SET pgid=? WHERE id=?", os.getpgid(p.pid), jid)
        self.event(jid, "stdout", "started: %s (timeout %ds)" % (" ".join(c["argv"][:1]) + (" …" if len(c["argv"]) > 1 else ""), timeout))
        deadline = now() + timeout
        total = 0
        timed_out = False

        def watchdog():
            nonlocal timed_out
            while p.poll() is None:
                if now() > deadline:
                    timed_out = True
                    self.kill(h)
                    return
                time.sleep(0.5)
        threading.Thread(target=watchdog, daemon=True).start()
        for line in p.stdout:
            total += len(line)
            if total > self.cfg.limits["max_output_bytes"]:
                self.event(jid, "stderr", "output limit reached; the process was stopped")
                self.kill(h)
                break
            self.event(jid, "stdout", line.rstrip("\n"))
        p.wait()
        return -9999 if timed_out else p.returncode

    def collect_artifacts(self, jid, cid, workdir):
        c = self.cfg.commands[cid]
        base = os.path.join(self.store.dir, "artifacts", jid)
        for pat in c.get("artifacts", []):
            for fp in glob.glob(os.path.join(workdir, pat), recursive=True):
                try:
                    rp = os.path.realpath(fp)
                    if not rp.startswith(os.path.realpath(workdir) + os.sep) or not os.path.isfile(rp) or is_secret_path(rp):
                        continue
                    size = os.path.getsize(rp)
                    if size > self.cfg.limits["max_artifact_bytes"]:
                        self.event(jid, "stderr", "artifact too large, skipped: " + os.path.basename(rp))
                        continue
                    name = re.sub(r"[^A-Za-z0-9._ +()-]", "_", os.path.basename(rp))[:120].lstrip(".") or "artifact"
                    os.makedirs(base, exist_ok=True)
                    dest = os.path.join(base, name)
                    shutil.copy2(rp, dest)
                    h = hashlib.sha256(open(dest, "rb").read()).hexdigest()
                    ext = name.rsplit(".", 1)[-1].lower() if "." in name else ""
                    kind = {"apk": "apk", "patch": "patch", "diff": "patch", "png": "screenshot", "jpg": "screenshot", "jpeg": "screenshot", "html": "report", "md": "report", "txt": "report", "log": "report", "json": "report"}.get(ext, "file")
                    mime = {"apk": "application/vnd.android.package-archive", "png": "image/png", "jpg": "image/jpeg", "jpeg": "image/jpeg", "html": "text/html", "md": "text/markdown", "json": "application/json"}.get(ext, "text/plain" if kind in ("report", "patch") else "application/octet-stream")
                    self.store.x("INSERT OR REPLACE INTO artifacts(job,name,kind,size,sha256,path,mime) VALUES(?,?,?,?,?,?,?)", jid, name, kind, size, h, dest, mime)
                    self.event(jid, "stdout", "artifact: %s (%d bytes, sha256 %s)" % (name, size, h))
                except OSError as e:
                    self.event(jid, "stderr", "artifact skipped: %s" % e)

    # ----- routines -----
    def routines_tick(self):
        t = now()
        for r in self.store.q("SELECT * FROM routines WHERE paused=0 AND next_run IS NOT NULL AND next_run<=?", t):
            d = json.loads(r["def"])
            scheduled = r["next_run"]
            late = t - scheduled
            nxt = next_after(d["schedule"], d.get("tz", "UTC"), t)
            self.store.x("UPDATE routines SET next_run=? WHERE uid=?", nxt, r["uid"])
            if late > 90 and d.get("missed", "skip") == "skip":
                self.store.x("INSERT OR IGNORE INTO routine_runs(uid,scheduled,started,status,note) VALUES(?,?,?,?,?)", r["uid"], scheduled, t, "missed", "the runner was not running at the scheduled time; skipped by policy")
                continue
            active = self.store.q("SELECT COUNT(*) n FROM routine_runs rr JOIN jobs j ON j.id=rr.job WHERE rr.uid=? AND j.state IN ('queued','running','awaiting_approval')", r["uid"])[0]["n"]
            if active and d.get("overlap", "skip") == "skip":
                self.store.x("INSERT OR IGNORE INTO routine_runs(uid,scheduled,started,status,note) VALUES(?,?,?,?,?)", r["uid"], scheduled, t, "skipped_overlap", "the previous run was still active")
                continue
            if self.store.x("INSERT OR IGNORE INTO routine_runs(uid,scheduled,started,status) VALUES(?,?,?,'started')", r["uid"], scheduled, t) == 0:
                continue  # replay: this scheduled time was already handled
            try:
                v = self.submit({"request_id": "routine:%s:%d" % (r["uid"], int(scheduled)), "workspace": d["workspace"], "tool": d["tool"], "args": d.get("args", {}), "title": "routine: " + d.get("name", "")[:80]}, origin="routine")
                self.store.x("UPDATE routine_runs SET job=? WHERE uid=? AND scheduled=?", v["id"], r["uid"], scheduled)
            except Exception as e:
                self.store.x("UPDATE routine_runs SET status='failed',note=? WHERE uid=? AND scheduled=?", str(e)[:200], r["uid"], scheduled)

    def put_routine(self, uid, d):
        if not re.fullmatch(r"[A-Za-z0-9_-]{8,64}", uid):
            raise ValueError("bad routine id")
        for k in ("workspace", "tool", "schedule"):
            if k not in d:
                raise ValueError("missing " + k)
        if d["workspace"] not in self.cfg.workspaces or d["tool"] not in {t["name"] for t in self.catalogue()}:
            raise ValueError("unknown workspace or tool")
        nxt = next_after(d["schedule"], d.get("tz", "UTC"), now())
        paused = 0 if d.get("enabled", True) else 1
        self.store.x("INSERT INTO routines(uid,def,paused,next_run,updated) VALUES(?,?,?,?,?) ON CONFLICT(uid) DO UPDATE SET def=excluded.def,paused=excluded.paused,next_run=excluded.next_run,updated=excluded.updated",
                     uid, json.dumps(d), paused, nxt, now())
        return {"uid": uid, "next_run": nxt, "paused": bool(paused)}

    # ----- MCP connectors (read-only allow-list) -----
    def _connector_client(self, cid, wsid):
        c = self.cfg.connectors.get(cid)
        if not c:
            raise KeyError("unknown connector")
        if wsid not in self.cfg.workspaces:
            raise ValueError("unknown workspace")
        key = (cid, wsid)
        with self.mx:
            cl = self.connectors.get(key)
            if not cl or not cl.alive():
                argv = [a.replace("{workspace}", self.cfg.workspaces[wsid]["path"]) for a in c["argv"]]
                cl = McpClient(argv)
                cl.start()
                self.connectors[key] = cl
        return c, cl

    def connector_call(self, cid, tool, args, wsid):
        c = self.cfg.connectors.get(cid)
        if not c:
            raise KeyError("unknown connector")
        if tool not in c.get("allow_tools", []):
            raise PermissionError("tool is not on this connector's read-only allow-list")
        c, cl = self._connector_client(cid, wsid)
        res = cl.call(tool, args)
        return res

    def connector_tools(self, cid, wsid):
        """Schemas of the connector's tools, limited to the owner's read-only allow-list. A tool the
        server offers but the allow-list omits is never described, so a model cannot even ask for it."""
        c, cl = self._connector_client(cid, wsid)
        allow = set(c.get("allow_tools", []))
        out = []
        for t in cl.list_tools():
            if t.get("name") in allow:
                schema = t.get("inputSchema")
                if not isinstance(schema, dict) or schema.get("type") != "object":
                    schema = {"type": "object", "properties": {}}
                out.append({"name": t["name"], "description": redact(str(t.get("description", "")))[:1000], "input_schema": schema})
        return {"connector": cid, "workspace": wsid, "read_only": True, "tools": out}


class McpClient:
    """Minimal MCP stdio client (newline-delimited JSON-RPC)."""
    def __init__(self, argv):
        self.argv = argv
        self.p = None
        self.n = 0
        self.lock = threading.Lock()

    def alive(self):
        return self.p is not None and self.p.poll() is None

    def start(self):
        self.p = subprocess.Popen(self.argv, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, bufsize=1,
                                  env={k: v for k, v in os.environ.items() if k in ("PATH", "HOME", "LANG", "NODE_OPTIONS", "npm_config_cache", "XDG_CACHE_HOME")}, start_new_session=True)
        r = self.rpc("initialize", {"protocolVersion": "2025-03-26", "capabilities": {}, "clientInfo": {"name": "victorbots-runner", "version": VERSION}}, timeout=90)
        if "error" in r:
            raise RuntimeError("MCP initialize failed")
        self.send({"jsonrpc": "2.0", "method": "notifications/initialized"})

    def send(self, o):
        self.p.stdin.write(json.dumps(o) + "\n")
        self.p.stdin.flush()

    def rpc(self, method, params, timeout=30):
        with self.lock:
            self.n += 1
            rid = self.n
            self.send({"jsonrpc": "2.0", "id": rid, "method": method, "params": params})
            end = now() + timeout
            result = {}
            done = threading.Event()

            def reader():
                nonlocal result
                while True:
                    line = self.p.stdout.readline()
                    if not line:
                        result = {"error": {"message": "server closed"}}
                        break
                    try:
                        m = json.loads(line)
                    except ValueError:
                        continue
                    if m.get("id") == rid:
                        result = m
                        break
                done.set()
            threading.Thread(target=reader, daemon=True).start()
            if not done.wait(max(0.1, end - now())):
                try: os.killpg(os.getpgid(self.p.pid), signal.SIGKILL)
                except Exception: pass
                raise TimeoutError("MCP call timed out")
            return result

    def list_tools(self):
        tools, cursor = [], None
        for _ in range(10):  # bounded paging
            r = self.rpc("tools/list", {"cursor": cursor} if cursor else {}, timeout=30)
            if "error" in r:
                raise RuntimeError("MCP error: " + str(r["error"].get("message", ""))[:200])
            res = r.get("result", {})
            tools.extend(t for t in res.get("tools", []) if isinstance(t, dict))
            cursor = res.get("nextCursor")
            if not cursor or len(tools) >= 500:
                break
        return tools[:500]

    def call(self, tool, args):
        r = self.rpc("tools/call", {"name": tool, "arguments": args}, timeout=30)
        if "error" in r:
            raise RuntimeError("MCP error: " + str(r["error"].get("message", ""))[:200])
        out = r.get("result", {})
        text = []
        for c in out.get("content", []):
            if c.get("type") == "text":
                text.append(c.get("text", ""))
        joined = redact("\n".join(text))[:262144]
        return {"is_error": bool(out.get("isError")), "text": joined}


def next_after(schedule, tz, after):
    """Next run time (epoch seconds) strictly after `after`, in an IANA timezone. DST: a nonexistent
    local time is moved forward; an ambiguous one runs once (first occurrence)."""
    z = ZoneInfo(tz) if ZoneInfo and tz != "UTC" else timezone.utc
    t = schedule.get("type")
    if t == "interval":
        m = int(schedule["minutes"])
        if m < 5 or m > 7 * 24 * 60:
            raise ValueError("interval must be 5 minutes to 7 days")
        return after + m * 60
    if t not in ("daily", "weekly"):
        raise ValueError("unknown schedule type")
    try:
        hh, mm = [int(x) for x in str(schedule["time"]).split(":")]
    except (KeyError, ValueError):
        raise ValueError("time must be HH:MM")
    if not (0 <= hh < 24 and 0 <= mm < 60):
        raise ValueError("bad time")
    days = set(schedule.get("days", range(7))) if t == "weekly" else set(range(7))
    if t == "weekly" and (not days or any(d < 0 or d > 6 for d in days)):
        raise ValueError("bad days")
    start = datetime.fromtimestamp(after, z)
    for add in range(0, 9):
        d = (start + timedelta(days=add)).date()
        if d.weekday() not in days:
            continue
        local = datetime(d.year, d.month, d.day, hh, mm, tzinfo=z)
        ts = local.astimezone(timezone.utc).timestamp()
        # normalise a nonexistent local time forward
        back = datetime.fromtimestamp(ts, z)
        if (back.hour, back.minute) != (hh, mm):
            ts = ts  # already moved forward by astimezone
        if ts > after:
            return ts
    raise ValueError("no next run found")


class Handler(BaseHTTPRequestHandler):
    server_version = "VictorBotsRunner/" + VERSION
    protocol_version = "HTTP/1.0"
    runner = None
    fails = {}

    def log_message(self, fmt, *a):
        pass

    def send_json(self, status, obj):
        b = json.dumps(obj).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(b)

    def authed(self):
        ip = self.client_address[0]
        f = Handler.fails.get(ip, (0, 0))
        if f[0] >= 8 and now() - f[1] < 60:
            self.send_json(429, {"error": "too many failed attempts; wait a minute"})
            return False
        h = self.headers.get("Authorization", "")
        tok = h[7:] if h.lower().startswith("bearer ") else ""
        ok = hmac.compare_digest(hashlib.sha256(tok.encode()).hexdigest(), self.runner.cfg.token_sha256)
        if not ok:
            Handler.fails[ip] = (f[0] + 1 if now() - f[1] < 60 else 1, now())
            time.sleep(0.3)
            self.send_json(401, {"error": "unauthorized"})
            return False
        Handler.fails.pop(ip, None)
        return True

    def body(self, limit=65536):
        n = int(self.headers.get("Content-Length") or 0)
        if n > limit:
            raise ValueError("request body too large")
        raw = self.rfile.read(n) if n else b""
        return json.loads(raw) if raw else {}

    def do_GET(self): self.route("GET")
    def do_POST(self): self.route("POST")
    def do_PUT(self): self.route("PUT")
    def do_DELETE(self): self.route("DELETE")

    def route(self, method):
        if not self.authed():
            return
        u = urlparse(self.path)
        p, qs = u.path, parse_qs(u.query)
        r = self.runner
        try:
            if p.startswith("/runner/v1/"):
                return self.runner_api(method, p[len("/runner/v1"):], qs)
            if p in ("/v1/models", "/v1/chat/completions", "/api/version", "/api/show", "/api/tags"):
                return self.proxy(method, p)
            self.send_json(404, {"error": "not found"})
        except KeyError as e:
            self.send_json(404, {"error": str(e).strip("'")})
        except PermissionError as e:
            self.send_json(403, {"error": str(e)})
        except (ValueError, json.JSONDecodeError) as e:
            self.send_json(400, {"error": str(e)[:200]})
        except TimeoutError as e:
            self.send_json(504, {"error": str(e)})
        except Exception as e:
            sys.stderr.write("handler error: %s\n" % redact(repr(e)))
            self.send_json(500, {"error": "internal error"})

    def runner_api(self, method, p, qs):
        r = self.runner
        if p == "/capabilities" and method == "GET":
            return self.send_json(200, r.capabilities())
        if p == "/jobs" and method == "POST":
            v = r.submit(self.body())
            return self.send_json(200 if v.get("duplicate") else 202, {"job": v})
        if p == "/jobs" and method == "GET":
            rid = (qs.get("request_id") or [""])[0]
            if rid:
                rows = r.store.q("SELECT id FROM jobs WHERE request_id=?", rid)
            else:
                rows = r.store.q("SELECT id FROM jobs ORDER BY created DESC LIMIT 50")
            return self.send_json(200, {"jobs": [r.job_view(x["id"]) for x in rows]})
        m = re.fullmatch(r"/jobs/([A-Za-z0-9_-]{1,64})(?:/(events|cancel|approve|artifacts/([^/]+)))?", p)
        if m:
            jid, sub, art = m.group(1), m.group(2), m.group(3)
            v = r.job_view(jid)
            if v is None:
                raise KeyError("no such job")
            if sub is None and method == "GET":
                return self.send_json(200, {"job": v})
            if sub == "events" and method == "GET":
                after = int((qs.get("after") or ["0"])[0])
                ev = r.store.q("SELECT seq,ts,stream,text FROM events WHERE job=? AND seq>? ORDER BY seq LIMIT 500", jid, after)
                return self.send_json(200, {"job": v, "events": ev, "last_seq": v["last_seq"]})
            if sub == "cancel" and method == "POST":
                return self.send_json(200, {"job": r.cancel(jid)})
            if sub == "approve" and method == "POST":
                d = self.body().get("decision")
                if d not in ("allow", "deny"):
                    raise ValueError("decision must be allow or deny")
                return self.send_json(200, {"job": r.approve(jid, d == "allow")})
            if art and method == "GET":
                name = unquote(art)
                a = r.store.q("SELECT path,size,sha256,mime FROM artifacts WHERE job=? AND name=?", jid, name)
                if not a or not a[0]["path"] or not os.path.realpath(a[0]["path"]).startswith(os.path.realpath(r.store.dir)):
                    raise KeyError("no such artifact")
                data = open(a[0]["path"], "rb").read()
                self.send_response(200)
                self.send_header("Content-Type", "application/octet-stream")
                self.send_header("Content-Length", str(len(data)))
                self.send_header("X-Content-SHA256", a[0]["sha256"])
                self.end_headers()
                self.wfile.write(data)
                return
        if p == "/connectors" and method == "GET":
            return self.send_json(200, {"connectors": [{"id": k, "type": v.get("type"), "tools": v.get("allow_tools", []), "read_only": True} for k, v in r.cfg.connectors.items()]})
        m = re.fullmatch(r"/connectors/([A-Za-z0-9_-]{1,40})/tools", p)
        if m and method == "GET":
            return self.send_json(200, r.connector_tools(m.group(1), (qs.get("workspace") or [""])[0]))
        m = re.fullmatch(r"/connectors/([A-Za-z0-9_-]{1,40})/call", p)
        if m and method == "POST":
            b = self.body()
            return self.send_json(200, r.connector_call(m.group(1), b.get("tool", ""), b.get("args") or {}, b.get("workspace", "")))
        if p == "/routines" and method == "GET":
            return self.send_json(200, {"routines": [dict(uid=x["uid"], paused=bool(x["paused"]), next_run=x["next_run"], def_=json.loads(x["def"])) for x in r.store.q("SELECT * FROM routines")]})
        m = re.fullmatch(r"/routines/([A-Za-z0-9_-]{8,64})(?:/(runs))?", p)
        if m:
            uid, sub = m.group(1), m.group(2)
            if sub == "runs" and method == "GET":
                runs = r.store.q("SELECT rr.scheduled,rr.started,rr.status,rr.note,rr.job,j.state job_state,j.exit_code FROM routine_runs rr LEFT JOIN jobs j ON j.id=rr.job WHERE rr.uid=? ORDER BY rr.scheduled DESC LIMIT 50", uid)
                return self.send_json(200, {"runs": runs})
            if method == "PUT":
                return self.send_json(200, r.put_routine(uid, self.body()))
            if method == "DELETE":
                r.store.x("DELETE FROM routines WHERE uid=?", uid)
                return self.send_json(200, {"deleted": True})
        self.send_json(404, {"error": "not found"})

    def proxy(self, method, path):
        up = self.runner.cfg.upstream
        if not up:
            return self.send_json(404, {"error": "inference pass-through is not configured on this runner"})
        body = self.body(8 * 1024 * 1024) if method == "POST" else None
        conn = (http.client.HTTPSConnection if up.scheme == "https" else http.client.HTTPConnection)(up.hostname, up.port or (443 if up.scheme == "https" else 80), timeout=600)
        try:
            hdrs = {"Content-Type": "application/json", "Accept": self.headers.get("Accept", "*/*")}
            conn.request(method, path, body=json.dumps(body).encode() if body is not None else None, headers=hdrs)  # the phone's token is NOT forwarded
            resp = conn.getresponse()
            self.send_response(resp.status)
            self.send_header("Content-Type", resp.getheader("Content-Type", "application/json"))
            self.end_headers()
            while True:
                chunk = resp.read(4096) if resp.getheader("Content-Type", "").startswith("text/event-stream") is False else resp.read1(4096)
                if not chunk:
                    break
                self.wfile.write(chunk)
                self.wfile.flush()
        except (ConnectionError, OSError) as e:
            try:
                self.send_json(502, {"error": "inference upstream unreachable"})
            except Exception:
                pass
        finally:
            conn.close()


def serve(cfg, ready=None):
    runner = Runner(cfg)
    Handler.runner = runner
    srv = ThreadingHTTPServer((cfg.host, cfg.port), Handler)
    srv.daemon_threads = True
    if cfg.cert and cfg.key:
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.minimum_version = ssl.TLSVersion.TLSv1_2
        ctx.load_cert_chain(cfg.cert, cfg.key)
        srv.socket = ctx.wrap_socket(srv.socket, server_side=True)
    elif cfg.host not in ("127.0.0.1", "::1", "localhost"):
        raise SystemExit("refusing to listen on a non-loopback address without TLS (set listen.cert and listen.key)")
    threading.Thread(target=runner.scheduler_loop, daemon=True).start()
    if ready:
        ready(srv, runner)
    sys.stderr.write("victorbots-runner %s listening on %s:%d (%s)\n" % (VERSION, cfg.host, srv.server_address[1], "TLS" if cfg.cert else "loopback, no TLS"))
    try:
        srv.serve_forever()
    finally:
        runner.stop.set()


def main():
    ap = argparse.ArgumentParser(description="VictorBots PC runner")
    ap.add_argument("--config")
    ap.add_argument("--hash-token", action="store_true", help="read a token on stdin and print its SHA-256 for the config")
    a = ap.parse_args()
    if a.hash_token:
        print(hashlib.sha256(sys.stdin.readline().strip().encode()).hexdigest())
        return
    if not a.config:
        ap.error("--config is required")
    with open(a.config) as f:
        cfg = Config(json.load(f))
    serve(cfg)


if __name__ == "__main__":
    main()
