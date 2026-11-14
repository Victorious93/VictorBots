# VictorBots PC runner

A small, stdlib-only Python service (Python 3.9+, Ubuntu 22.04/24.04) that gives the VictorBots phone app authenticated, bounded access to your PC. **It is not a general remote shell.**

## What it is and is not
* Serves HTTPS only (it refuses to listen on a non-loopback address without a certificate) and requires `Authorization: Bearer <token>` on every request, including model pass-through. Only the token's SHA-256 is stored. Eight bad tokens per minute per address locks that address out for a minute.
* Runs **only**: the built-in read tools (`repo.list`, `repo.read`, `repo.search`, `git.status`, `git.diff`) inside configured workspaces, and the **named commands you put in the config file**. The phone sends a command *name*; it cannot supply argv, a path outside a workspace, or environment. Secret-looking files (`.env`, `*.pem`, `*.key`, keystores, `id_*`) are never read. Git runs with hooks, fsmonitor and external diff disabled.
* Commands may require approval (default **yes**). A job waiting for approval expires (`approval_ttl_s`, default 600 s) and then never runs. Approving is a separate authenticated request, so a bot cannot approve its own job.
* Write commands on one workspace run **one at a time** (`"isolation":"lock"`, default) or each in its own throw-away `git worktree` (`"isolation":"worktree"`, uncommitted changes are not copied).
* Output is redacted (tokens, keys, bearer headers, private-key blocks) and capped; every job has a timeout; `cancel` kills the process group. On restart, jobs that were running are marked **failed** with that reason.
* Model pass-through (`/v1/models`, `/v1/chat/completions`, `/api/version|show|tags`) forwards to `inference_upstream`, which must be loopback unless you set `allow_remote_upstream`. The phone's token is **not** forwarded upstream.
* Optional **read-only connectors**: an MCP server started on demand with `{workspace}` substituted; only tools in `allow_tools` can be called. Nothing is installed for you.
* Provider CLIs (Claude Code, Codex, Gemini CLI) are not integrated. If you add one yourself as a command, its login stays on this PC.

## Install (Ubuntu)
```bash
sudo bash install-ubuntu.sh --host 192.168.1.20 --workspace /home/me/work/app \
     --inference-upstream http://127.0.0.1:11434        # last flag only if you run Ollama/llama.cpp
```
Creates `/opt/victorbots-runner`, `/etc/victorbots-runner/{config.json,cert.pem,key.pem}`, `/var/lib/victorbots-runner`, a `victorbots` system user and a hardened systemd unit (`NoNewPrivileges`, `ProtectSystem=strict`, `ProtectHome=read-only`). It prints the address, a one-time token and the certificate fingerprint to paste into the phone. It does **not** modify the firewall: allow the port only from your phone/VPN (`sudo ufw allow from <subnet> to any port 8443 proto tcp`). Do not forward it on your router.

Because the unit runs with `ProtectHome=read-only`, a *write* command needs its workspace listed in `ReadWritePaths=` of the unit (`systemctl edit victorbots-runner`). That is deliberate.

Nothing here was run under systemd in the environment that built it; the non-systemd path (`--prefix DIR --no-systemd`) was.

## Config reference (`config.json`)
```json
{
  "listen": {"host": "192.168.1.20", "port": 8443, "cert": ".../cert.pem", "key": ".../key.pem"},
  "token_sha256": "<hex>",
  "state_dir": "/var/lib/victorbots-runner",
  "inference_upstream": "http://127.0.0.1:11434",
  "workspaces": {"main": {"path": "/home/me/work/app", "mode": "ro"},
                 "build": {"path": "/home/me/work/app", "mode": "rw", "isolation": "worktree"}},
  "commands": {
    "unit-tests": {"argv": ["./gradlew", "test"], "cwd": ".", "workspace_write": true, "requires_approval": true,
                    "timeout_s": 1800, "artifacts": ["app/build/reports/**/*.html"], "description": "Run the unit tests"}
  },
  "connectors": {"repo-files": {"type": "mcp-stdio", "argv": ["npx", "-y", "@modelcontextprotocol/server-filesystem@2.3.0", "{workspace}"],
                                "allow_tools": ["read_text_file", "list_directory", "search_files"]}},
  "limits": {"max_running": 2, "approval_ttl_s": 600, "max_job_seconds": 3600}
}
```
A new token: `printf '%s' "$TOKEN" | python3 victorbots_runner.py --hash-token` and put the hash in `token_sha256`.

## API (protocol 1, all under `/runner/v1`)
`GET /capabilities` · `POST /jobs` `{request_id, workspace, tool, args, title}` (idempotent by `request_id`; 202 new, 200 duplicate) · `GET /jobs?request_id=` · `GET /jobs/{id}` · `GET /jobs/{id}/events?after=N` · `POST /jobs/{id}/cancel` · `POST /jobs/{id}/approve {decision}` · `GET /jobs/{id}/artifacts/{name}` (`X-Content-SHA256`) · `GET /connectors` · `POST /connectors/{id}/call` · `PUT|DELETE /routines/{uid}` · `GET /routines/{uid}/runs`.

## Tests
`python3 -I test_runner.py` (schedules incl. DST, routine replay/missed/overlap/approval, worktree isolation, proxy token handling, MCP client vs a fixture). `VB_REAL_MCP=1 python3 -I test_runner.py Mcp` additionally downloads and runs the real MCP filesystem server (requires npm access).
