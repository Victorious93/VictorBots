# VictorBots 0.2.0 — owner manual

Plain rules first: **a bot's words are never proof that work happened**, **nothing leaves the phone unless your policy allows that destination**, and **only a backend's own report marks a job complete**.

## 1. Where things run (and what if that device is offline)

| Thing | Runs on | If that device is offline |
|---|---|---|
| Chat with a cloud profile | the provider's servers | Request fails with a category (timeout/unreachable). Nothing is retried. |
| Chat with a PC profile | your PC's model server | Same. Under "PC first" you may be offered a one-request cloud retry. |
| Chat via DroidCommand | this phone, then wherever DroidCommand sends it | DroidCommand missing → the request is refused; nothing assumed. |
| Team rounds, memory suggestions, summaries | each bot's own profile | A bot whose profile is unreachable reports its failure; others continue. |
| PC jobs and PC routines | your PC runner | Phone offline: they keep running; the phone shows the last reported state and catches up when it reconnects. PC offline: a job you start fails to send (you are told); a routine's scheduled time is *missed* or run once later, per its setting. |
| DroidCommand jobs | this phone, in DroidCommand, after you tap **Allow** there | DroidCommand missing/old: refused, job becomes *outcome unknown*, never repeated. |
| Phone routines | this phone, when Android wakes the app (about every 15 min at best; later in battery saver) | Phone off: the run is *missed* (or run once when it wakes). |

## 2. Provider profiles (cloud)

More → Providers → *Add a cloud provider*. Enter the API base URL (must be `https://`, usually ending in `/v1`), the model id (or **Discover models** after saving), and the key. Notes:

* The key is stored encrypted and bound to that endpoint. **Changing the endpoint to another host deletes the key** and asks you to enter it again.
* "Context window" is what *you* set from your provider's documentation; VictorBots does not guess it. Until you set it, it assumes 4096 tokens and says so.
* Streaming, tools, images and reasoning effort are switches *you* turn on. Only what you enable is sent. **Check connection** shows what the server answered; **Live test** sends one tiny message (it shows the destination and warns that a provider may bill it). "Accepted" is not "effective": a server accepting `reasoning_effort` does not prove it did anything.
* Prices are optional, entered by you, and only produce a labelled **estimate**. For a flat subscription the number is shown as "API-equivalent, NOT a charge".
* Provider behaviour was checked against Ollama's current documentation. OpenAI's documentation host could not be reached from the build environment, so OpenAI-specific parameters are not asserted; only the minimal request (`model`, `messages`, `stream`, and `stream_options.include_usage` when streaming) is sent by default.

## 3. Connecting your PC securely

"localhost" and "127.0.0.1" on a phone mean **the phone**. Use your PC's LAN address, VPN address (Tailscale/WireGuard) or DNS name.

1. On the PC (Ubuntu 22.04/24.04): `sudo bash pc-runner/install-ubuntu.sh --host <pc-ip-or-name> --workspace ~/work/myrepo --inference-upstream http://127.0.0.1:11434` (the last flag is only for Ollama/llama.cpp). It prints, **once**, the HTTPS address, an access token and the certificate SHA-256 fingerprint. It does not touch your firewall; allow the port only from your phone or VPN.
2. In VictorBots: *Add my PC*. Address `https://<pc>:8443/v1`, the token, and (because the installer's certificate is self-signed) the fingerprint. Only that exact certificate is trusted, only for that profile. A publicly trusted certificate (for example from `tailscale cert`) needs no fingerprint.
3. Press **Check connection**. You should see saved ✓ reachable ✓ credentials accepted ✓ model available ✓. "Real reply received" stays "not run" until you press **Live test**.

**VictorLinux (optional).** If you run VictorLinux on a PC, *Add VictorLinux PC* fills in the endpoint for you (host, port, token and certificate fingerprint are all required). See `docs/VICTORLINUX.md` for how to get the fingerprint, what capability detection does, and the runner-URL rules. A PC token is stored bound to the address **and** the certificate fingerprint: if you change either, enter the token again. After updating from an earlier 0.2.0 build, a PC profile that has a fingerprint asks for its token once more.

VictorBots never enables app-wide cleartext, never trusts all certificates, and refuses a PC profile that has no token. Ollama itself has no authentication: expose it only through the runner (or your own TLS+auth proxy), not directly on `0.0.0.0`.

**Text model ≠ execution.** A PC-hosted model only answers text. Running commands requires the runner's *jobs*, which are limited to the workspaces and commands in its config file. Provider CLIs (Claude Code, Codex, Gemini CLI) are **not** integrated; if you add one as a runner command, its login stays on the PC and VictorBots never sees it.

## 4. Policy

More → Providers → *Change policy*:

* **Cloud allowed** — any configured profile.
* **PC first, cloud only if I confirm** — PC profiles work normally. Cloud (or DroidCommand) is used only after you confirm *that one request* in a dialog. Unattended work (routing, memory, summaries, routines, team rounds) never goes to cloud.
* **PC only** — only PC profiles; cloud and DroidCommand are refused.
* **This device only** — listed but unavailable: VictorBots has no on-device model and cannot see where DroidCommand sends text.

A bot may be set stricter than your policy, never looser. Text inside messages, files, memories, skills or backups cannot change policy. Every request (allowed or refused) is listed in More → Usage with its destination.

## 5. Memory

Open a bot → ⋮ → Memory for this bot (or More → Memory). Long-press a reply → *Remember this* saves it. *Suggest memories from this chat* sends your messages to the bot's provider (policy applies, destination shown) and stores suggestions **inactive** until you accept each one. Edit, pin, search and forget at any time. Project memory is shared between bots with the same *project* name only while you switch sharing on. Memory is quoted to the model as background, not commands.

## 6. Team chats

Teams → New team chat. Pick 2–8 bots and a reply mode. Address a bot with `@Name` (this overrides mute). Limits per round (turns, hand-offs, tokens, seconds) are editable; **Stop** ends a round. Notice lines show how routing decided, what was suppressed as a duplicate, and why a round ended. A bot that cannot answer (policy, provider down, DroidCommand profile) says so and the others continue. A bot may write a ```` ```victorbots-job {...}``` ```` block; it only appears as **Review proposed job** and does nothing until you approve and run it.

## 7. Jobs and approvals

Jobs tab. Create a draft (PC runner tool, DroidCommand capability, or manual note), then **Run**. States you will see: *Draft/Proposed* (nothing sent) → *Sent, no answer yet* → *Queued/Running/Awaiting approval/Completed/Failed/Cancelled* **as reported by the backend**, or *Outcome unknown*. Approvals: a PC job that needs approval waits on the runner (it expires on its own); your tap is sent to the runner. For DroidCommand, the Allow tap happens **inside DroidCommand**; VictorBots only shows the receipt. Logs are redacted and the last 2,000 lines are kept. Artifacts are downloaded only on request, verified against the runner's SHA-256, previewed as text/image only, and **never installed** (APKs: compare the checksum, then install yourself).

## 8. Skills, files, routines, connectors, backup

* Skills: type `/repo-review` etc. in a message. Needs are informational.
* Files: 📎 in a chat. Text/source ≤ 60,000 characters (larger is refused, not cut), logs are visibly shortened keeping the end, images ≤ 4 MB and only for profiles with image input switched on. PDFs, Office files, archives and APKs are refused with a reason.
* Routines: More → Routines. Created off. See §1 for where they run and what happens when a device is offline.
* Connectors: configure an MCP server in the runner's config (read-only tool allow-list), then More → Connectors → Load → enable for a bot. In chat: 📎 → *Read from a repository connector*.
* Backup: More → Backup. Restore previews first; default keeps your data on conflict. Keys/tokens are never in a backup, so re-enter them after restoring on a new phone.

## 9. Troubleshooting

| Symptom | Likely cause / action |
|---|---|
| "TLS failed … pinned fingerprint" | The PC's certificate changed. Re-run the installer or paste the new fingerprint. |
| "Host unreachable" | Wrong address, phone not on the same network/VPN, firewall, runner not running (`systemctl status victorbots-runner`). |
| "Authentication rejected" | Wrong token. Tokens are shown once; generate a new config token with `victorbots_runner.py --hash-token`. |
| "Model unavailable" | Pick from **Discover models** or type the exact id. |
| "Nothing was sent" with a policy sentence | Policy refused it. Change the policy or the bot's profile on purpose. |
| Job *outcome unknown* | The connection broke after sending. Use **Check status**; if the runner has no record you may start a new job. |
| DroidCommand refuses | It must include `COMPANION_EXEC`, be paired and match the pinned certificate (More → DroidCommand link). |
| Estimated tokens | The server did not report usage; figures are labelled estimates. |
