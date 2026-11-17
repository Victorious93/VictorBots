import hashlib, http.client, http.server, json, os, subprocess, sys, tempfile, threading, time, unittest
from datetime import datetime, timezone
from zoneinfo import ZoneInfo
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import victorbots_runner as vr


def make_runner(extra=None, ws_isolation="lock"):
    d = tempfile.mkdtemp()
    ws = os.path.join(d, "ws")
    os.makedirs(ws)
    subprocess.run(["git", "-C", ws, "init", "-q"])
    open(os.path.join(ws, "a.txt"), "w").write("v1\n")
    g = ["git", "-C", ws, "-c", "user.name=t", "-c", "user.email=t@t"]
    subprocess.run(g + ["add", "."])
    subprocess.run(g + ["commit", "-qm", "i"])
    cfg = {"listen": {"host": "127.0.0.1", "port": 0}, "token_sha256": hashlib.sha256(b"t").hexdigest(),
           "state_dir": os.path.join(d, "state"),
           "workspaces": {"main": {"path": ws, "mode": "rw", "isolation": ws_isolation}},
           "commands": {"echo": {"argv": ["sh", "-c", "echo hi"], "requires_approval": False},
                        "slow": {"argv": ["sh", "-c", "sleep 3; echo done"], "requires_approval": False},
                        "wt": {"argv": ["sh", "-c", "echo changed > a.txt; pwd; sleep 1"], "workspace_write": True, "requires_approval": False}}}
    cfg.update(extra or {})
    r = vr.Runner(vr.Config(cfg))
    r.d = d
    r.ws = ws
    threading.Thread(target=r.scheduler_loop, daemon=True).start()
    return r


def wait(r, jid, states, secs=15):
    for _ in range(secs * 10):
        s = r.job_view(jid)["state"]
        if s in states:
            return s
        time.sleep(0.1)
    return r.job_view(jid)["state"]


class Schedule(unittest.TestCase):
    def ts(self, y, m, d, h, mi, tz):
        return datetime(y, m, d, h, mi, tzinfo=ZoneInfo(tz)).astimezone(timezone.utc).timestamp()

    def test_daily_local_time(self):
        n = vr.next_after({"type": "daily", "time": "09:00"}, "Europe/London", self.ts(2026, 3, 1, 12, 0, "Europe/London"))
        self.assertEqual(datetime.fromtimestamp(n, ZoneInfo("Europe/London")).strftime("%Y-%m-%d %H:%M"), "2026-03-02 09:00")

    def test_dst_spring_forward_keeps_local_wall_time(self):
        a = vr.next_after({"type": "daily", "time": "09:00"}, "Europe/London", self.ts(2026, 3, 27, 12, 0, "Europe/London"))
        b = vr.next_after({"type": "daily", "time": "09:00"}, "Europe/London", a)
        self.assertEqual(datetime.fromtimestamp(a, ZoneInfo("Europe/London")).hour, 9)
        self.assertEqual(datetime.fromtimestamp(b, ZoneInfo("Europe/London")).hour, 9)
        self.assertEqual((b - a) / 3600, 23)

    def test_nonexistent_local_time_runs_once_not_twice(self):
        a = vr.next_after({"type": "daily", "time": "01:30"}, "Europe/London", self.ts(2026, 3, 28, 12, 0, "Europe/London"))
        b = vr.next_after({"type": "daily", "time": "01:30"}, "Europe/London", a)
        self.assertGreater(b, a)
        self.assertLess(b - a, 49 * 3600)

    def test_weekly(self):
        n = vr.next_after({"type": "weekly", "days": [0], "time": "08:00"}, "UTC", self.ts(2026, 3, 4, 0, 0, "UTC"))
        self.assertEqual(datetime.fromtimestamp(n, timezone.utc).strftime("%a %H:%M"), "Mon 08:00")

    def test_bounds(self):
        with self.assertRaises(ValueError):
            vr.next_after({"type": "interval", "minutes": 1}, "UTC", 0)
        with self.assertRaises(ValueError):
            vr.next_after({"type": "daily", "time": "25:00"}, "UTC", 0)
        with self.assertRaises(ValueError):
            vr.next_after({"type": "cron"}, "UTC", 0)


class Routines(unittest.TestCase):
    def due(self, r, uid, ago=1):
        r.store.x("UPDATE routines SET next_run=? WHERE uid=?", time.time() - ago, uid)

    def test_due_routine_runs_once_and_replay_is_ignored(self):
        r = make_runner()
        r.put_routine("routine-aaaa1", {"name": "r", "workspace": "main", "tool": "cmd.echo", "schedule": {"type": "interval", "minutes": 5}, "tz": "UTC"})
        self.due(r, "routine-aaaa1")
        time.sleep(1.2)
        runs = r.store.q("SELECT * FROM routine_runs WHERE uid='routine-aaaa1'")
        self.assertEqual(len(runs), 1)
        self.assertEqual(wait(r, runs[0]["job"], {"completed"}), "completed")
        r.store.x("UPDATE routines SET next_run=? WHERE uid='routine-aaaa1'", runs[0]["scheduled"])
        time.sleep(1.2)
        self.assertEqual(len(r.store.q("SELECT * FROM routine_runs WHERE uid='routine-aaaa1'")), 1)
        self.assertEqual(r.store.q("SELECT COUNT(*) n FROM jobs WHERE origin='routine'")[0]["n"], 1)
        r.stop.set()

    def test_missed_run_skipped_by_default_and_recorded(self):
        r = make_runner()
        r.put_routine("routine-bbbb2", {"name": "m", "workspace": "main", "tool": "cmd.echo", "schedule": {"type": "interval", "minutes": 60}, "tz": "UTC", "missed": "skip"})
        self.due(r, "routine-bbbb2", 3600)
        time.sleep(1.2)
        self.assertEqual([x["status"] for x in r.store.q("SELECT status FROM routine_runs WHERE uid='routine-bbbb2'")], ["missed"])
        self.assertEqual(r.store.q("SELECT COUNT(*) n FROM jobs")[0]["n"], 0)
        r.stop.set()

    def test_missed_run_once_policy_runs_once(self):
        r = make_runner()
        r.put_routine("routine-cccc3", {"name": "m", "workspace": "main", "tool": "cmd.echo", "schedule": {"type": "interval", "minutes": 60}, "tz": "UTC", "missed": "run_once"})
        self.due(r, "routine-cccc3", 7200)
        time.sleep(1.5)
        self.assertEqual(r.store.q("SELECT COUNT(*) n FROM jobs")[0]["n"], 1)
        r.stop.set()

    def test_overlap_skip(self):
        r = make_runner()
        r.put_routine("routine-dddd4", {"name": "o", "workspace": "main", "tool": "cmd.slow", "schedule": {"type": "interval", "minutes": 5}, "tz": "UTC", "overlap": "skip"})
        self.due(r, "routine-dddd4")
        time.sleep(1.0)
        self.due(r, "routine-dddd4")
        time.sleep(1.0)
        st = sorted(x["status"] for x in r.store.q("SELECT status FROM routine_runs WHERE uid='routine-dddd4'"))
        self.assertEqual(st, ["skipped_overlap", "started"])
        r.stop.set()

    def test_paused_never_runs_and_unknown_tool_rejected(self):
        r = make_runner()
        r.put_routine("routine-eeee5", {"name": "p", "workspace": "main", "tool": "cmd.echo", "schedule": {"type": "interval", "minutes": 5}, "enabled": False})
        r.store.x("UPDATE routines SET next_run=?", time.time() - 1)
        time.sleep(1.0)
        self.assertEqual(r.store.q("SELECT COUNT(*) n FROM jobs")[0]["n"], 0)
        with self.assertRaises(ValueError):
            r.put_routine("routine-ffff6", {"workspace": "main", "tool": "cmd.rm", "schedule": {"type": "interval", "minutes": 5}})
        r.stop.set()

    def test_routine_cannot_self_approve_a_dangerous_tool(self):
        r = make_runner({"commands": {"danger": {"argv": ["sh", "-c", "echo ran"], "requires_approval": True}}, "limits": {"approval_ttl_s": 1}})
        r.put_routine("routine-gggg7", {"name": "d", "workspace": "main", "tool": "cmd.danger", "schedule": {"type": "interval", "minutes": 5}})
        r.store.x("UPDATE routines SET next_run=?", time.time() - 1)
        time.sleep(1.0)
        jid = r.store.q("SELECT id FROM jobs")[0]["id"]
        self.assertEqual(wait(r, jid, {"cancelled"}, 5), "cancelled")
        self.assertIn("expired", r.job_view(jid)["error"])
        r.stop.set()


class Isolation(unittest.TestCase):
    def test_worktree_isolation_leaves_main_tree_untouched(self):
        r = make_runner(ws_isolation="worktree")
        a = r.submit({"request_id": "wt-job-00001", "workspace": "main", "tool": "cmd.wt"})
        b = r.submit({"request_id": "wt-job-00002", "workspace": "main", "tool": "cmd.wt"})
        time.sleep(0.7)
        self.assertEqual({r.job_view(a["id"])["state"], r.job_view(b["id"])["state"]}, {"running"})
        self.assertEqual(wait(r, a["id"], {"completed", "failed"}), "completed")
        self.assertEqual(wait(r, b["id"], {"completed", "failed"}), "completed")
        self.assertEqual(open(os.path.join(r.ws, "a.txt")).read(), "v1\n")
        logs = " ".join(x["text"] for x in r.store.q("SELECT text FROM events WHERE job=?", a["id"]))
        self.assertIn("worktrees", logs)
        r.stop.set()


class Proxy(unittest.TestCase):
    def test_proxy_does_not_forward_token_and_refuses_unsafe_config(self):
        seen = {}

        class H(http.server.BaseHTTPRequestHandler):
            def do_POST(s):
                seen["auth"] = s.headers.get("Authorization")
                s.rfile.read(int(s.headers["Content-Length"]))
                s.send_response(200)
                s.send_header("Content-Type", "application/json")
                s.end_headers()
                s.wfile.write(b'{"ok":1}')

            def log_message(s, *a):
                pass

        up = http.server.HTTPServer(("127.0.0.1", 0), H)
        threading.Thread(target=up.serve_forever, daemon=True).start()
        d = tempfile.mkdtemp()
        cfg = {"listen": {"host": "127.0.0.1", "port": 0}, "token_sha256": hashlib.sha256(b"t").hexdigest(), "state_dir": d, "workspaces": {},
               "inference_upstream": "http://127.0.0.1:%d" % up.server_address[1]}
        vr.Handler.runner = vr.Runner(vr.Config(cfg))
        srv = vr.ThreadingHTTPServer(("127.0.0.1", 0), vr.Handler)
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        port = srv.server_address[1]
        c = http.client.HTTPConnection("127.0.0.1", port)
        c.request("POST", "/v1/chat/completions", body=b'{"model":"m"}', headers={"Authorization": "Bearer t", "Content-Type": "application/json"})
        r = c.getresponse()
        self.assertEqual(r.status, 200)
        self.assertEqual(r.read(), b'{"ok":1}')
        self.assertIsNone(seen["auth"])
        c = http.client.HTTPConnection("127.0.0.1", port)
        c.request("POST", "/v1/chat/completions", body=b"{}", headers={"Authorization": "Bearer wrong"})
        self.assertEqual(c.getresponse().status, 401)
        c = http.client.HTTPConnection("127.0.0.1", port)
        c.request("GET", "/v1/files", headers={"Authorization": "Bearer t"})
        self.assertEqual(c.getresponse().status, 404)
        with self.assertRaises(SystemExit):
            vr.Config(dict(cfg, inference_upstream="http://10.1.2.3:11434"))
        with self.assertRaises(SystemExit):
            vr.serve(vr.Config({"listen": {"host": "0.0.0.0", "port": 0}, "token_sha256": hashlib.sha256(b"t").hexdigest(), "state_dir": d}))
        srv.shutdown()
        up.shutdown()


FIXTURE = r"""
import json, sys, os
root = os.path.realpath(sys.argv[1])
for line in sys.stdin:
    m = json.loads(line)
    if "id" not in m:
        continue
    meth = m["method"]
    if meth == "initialize":
        res = {"protocolVersion": "2025-03-26", "capabilities": {"tools": {}}, "serverInfo": {"name": "fixture", "version": "0"}}
    elif meth == "tools/list":
        res = {"tools": [
            {"name": "read_text_file", "description": "Read a file token=abcdef123456", "inputSchema": {"type": "object", "properties": {"path": {"type": "string"}}, "required": ["path"]}},
            {"name": "write_file", "description": "Write a file", "inputSchema": {"type": "object", "properties": {"path": {"type": "string"}, "content": {"type": "string"}}}},
            {"name": "no_schema"}]}
    elif meth == "tools/call":
        a = m["params"]["arguments"]
        t = m["params"]["name"]
        if t == "write_file":
            open(a["path"], "w").write(a["content"])  # a dangerous tool the runner must never forward
            res = {"content": [{"type": "text", "text": "written"}]}
        else:
            p = os.path.realpath(a["path"])
            if not p.startswith(root):
                res = {"isError": True, "content": [{"type": "text", "text": "outside allowed directory"}]}
            else:
                res = {"content": [{"type": "text", "text": open(p).read() + " token=abcdef123456"}]}
    else:
        res = {}
    print(json.dumps({"jsonrpc": "2.0", "id": m["id"], "result": res}), flush=True)
"""


class McpFixture(unittest.TestCase):
    """Protocol-level test with a local FIXTURE server (not the real MCP filesystem server)."""
    def test_client_speaks_mcp_and_enforces_the_read_only_allow_list(self):
        ws = os.path.realpath(tempfile.mkdtemp())
        open(os.path.join(ws, "hello.txt"), "w").write("fixture-content")
        fx = os.path.join(ws, "..", "fx_%d.py" % os.getpid())
        open(fx, "w").write(FIXTURE)
        r = make_runner({"workspaces": {"main": {"path": ws, "mode": "ro"}}, "commands": {},
                         "connectors": {"repo-files": {"type": "mcp-stdio", "argv": [sys.executable, "-I", fx, "{workspace}"], "allow_tools": ["read_text_file"]}}})
        out = r.connector_call("repo-files", "read_text_file", {"path": os.path.join(ws, "hello.txt")}, "main")
        self.assertIn("fixture-content", out["text"])
        self.assertNotIn("abcdef123456", out["text"])  # secrets in tool output are redacted
        with self.assertRaises(PermissionError):
            r.connector_call("repo-files", "write_file", {"path": os.path.join(ws, "x"), "content": "x"}, "main")
        self.assertFalse(os.path.exists(os.path.join(ws, "x")))
        with self.assertRaises(KeyError):
            r.connector_call("nope", "read_text_file", {}, "main")
        with self.assertRaises(ValueError):
            r.connector_call("repo-files", "read_text_file", {}, "ghost-workspace")
        # The model-facing tool list contains only allow-listed tools, with their schemas.
        lst = r.connector_tools("repo-files", "main")
        self.assertEqual([x["name"] for x in lst["tools"]], ["read_text_file"])
        self.assertEqual(lst["tools"][0]["input_schema"]["required"], ["path"])
        self.assertNotIn("abcdef123456", json.dumps(lst))
        self.assertNotIn("write_file", json.dumps(lst))
        with self.assertRaises(ValueError):
            r.connector_tools("repo-files", "ghost-workspace")
        with self.assertRaises(KeyError):
            r.connector_tools("nope", "main")
        r.stop.set()


class Mcp(unittest.TestCase):
    @unittest.skipUnless(os.environ.get("VB_REAL_MCP"), "live check: set VB_REAL_MCP=1 to download and run the pinned official MCP filesystem server")
    def test_real_mcp_filesystem_server_read_only(self):
        ws = os.path.realpath(tempfile.mkdtemp())
        open(os.path.join(ws, "hello.txt"), "w").write("mcp-content-123\n")
        r = make_runner({"workspaces": {"main": {"path": ws, "mode": "ro"}}, "commands": {},
                         "connectors": {"repo-files": {"type": "mcp-stdio", "argv": ["npx", "-y", "@modelcontextprotocol/server-filesystem@2.3.0", "{workspace}"],
                                                       "allow_tools": ["read_text_file", "list_directory", "search_files"]}}})
        out = r.connector_call("repo-files", "read_text_file", {"path": os.path.join(ws, "hello.txt")}, "main")
        self.assertIn("mcp-content-123", out["text"])
        with self.assertRaises(PermissionError):
            r.connector_call("repo-files", "write_file", {"path": os.path.join(ws, "x"), "content": "x"}, "main")
        self.assertFalse(os.path.exists(os.path.join(ws, "x")))
        bad = r.connector_call("repo-files", "read_text_file", {"path": "/etc/passwd"}, "main")
        self.assertNotIn("root:", bad["text"])
        r.stop.set()


if __name__ == "__main__":
    unittest.main(verbosity=1)
