# Victorious Framework — VictorBots architecture (0.2.0 + tool loop, schema v3)

## Layers

```
MainActivity + screens (Ui, ProvidersScreen, TeamScreen, JobsScreen, MoreScreens)   Android views, no framework
        │ Host
BotApp (application scope: executors, recovery, job poller)        RoutineService (JobScheduler, phone routines)
        │
┌───────┴─────────────── pure Java, no android.* (unit-tested on JVM with real SQLite / real TLS) ───────────────┐
│ Gateway ──► Policy        every inference request, every purpose, one choke point; usage ledger                 │
│   │  Profiles/Profile/CapabilityReport/Diagnostics/UsageStats   Provider (HTTPS client) + Tls (pinning) + Redact │
│ PromptBuilder ─► Rules.plan (token budget) · Memory (FTS4) · Untrusted · MemoryAssistant                        │
│ TeamEngine ─► Teams · Mentions · Router                                                                         │
│ Jobs (state machine) · JobMonitor · RunnerClient · DcaExecCodec · JobProposals                                  │
│ Routines · Skills · Attachments · Backup · Migrations · Db (interface)                                          │
└────────────────────────────────────────────────────────────────────────────────────────────────────────────┘
Android adapters: Store (SQLiteOpenHelper) + AndroidDb · SecretStore (Keystore) · PrefsKV · DcaBridge · LegacyMigration
```

`Db` abstracts SQL so the same schema, migrations and stores run on Android SQLite (instrumentation) and on JDBC SQLite (JVM tests).

## Invariants (each has a test)

1. **One door for model text.** `Provider.chat` is called only from `Gateway`; `Provider.models/ollamaInfo` only from `Diagnostics` (which asks the gateway for a policy decision first). A source-scan test fails if any other file does. Policy is read from owner settings in `Gateway.plan` and nowhere else; effective mode = stricter(global, bot).
2. **Keys are bound.** `SecretStore` encrypts with Keystore AES-GCM using `profileId|binding` as associated data and stores the binding beside it. The binding (`Profile.binding`) is the endpoint origin, plus the certificate pin for PC profiles that have one. Reading for another binding throws; moving ciphertext to another profile id or editing the stored label fails authentication. `Profiles.save` deletes the key when the origin or (for PC) the pin changes. PC profiles without a token are never called. A token stored before pins were part of the binding is simply unusable until re-entered (`Secrets.usable`), never decrypted around the check.
3. **Untrusted by construction.** Other bots' messages, attachments, retrieved/generated text are wrapped by `Untrusted` and sent as user-role data; owner memory goes in the system block labelled as background. This lowers injection risk but is not the boundary: the boundary is that model output cannot change policy (`Gateway`), start work (`Jobs`: bot-originated jobs are `PROPOSED`, only the owner can approve then run), or complete work (`Jobs.applyRemote` only).
4. **Honest state.** Job states split *local* (draft/proposed/sent/unknown) from *backend-reported*. `COMPLETED` with a non-zero exit code is demoted to `FAILED`; terminal states are final; events are idempotent by sequence number; a send that may have reached the backend becomes `UNKNOWN` and is never resent (`markSubmitting` only accepts a draft).
5. **Migrations are explicit.** `Migrations.v1()` is 0.1.0's schema; `v2()` the upgrade (including the SQLite 12-step rebuild of `chats` with foreign keys off, re-enabled in `onOpen`, followed by `foreign_key_check`). Fresh installs run v1 then v2 (one path).

* **Optional VictorLinux preset** (`VictorLinux`, `docs/VICTORLINUX.md`): a PC profile marked `caps.preset`, with a required token and pin, an optional separate runner URL (`Profile.runner()`; a runner on another origin has its own token slot `-profileId` and pin) and a capability probe (`GET /victor/capabilities`, pinned, no redirects) that only pre-selects the tool-call switch. No schema change; nothing depends on it when no such profile exists.

* **Model-driven read-only tools** (`ToolLoop`, schema v3). Per bot, per connector the owner sets a runner workspace and switches "bot may call" on (`bot_connectors.auto/workspace`; existing rows stay off). In an owner-initiated `CHAT` only, the model is offered `connector__tool` functions = the runner's read-only allow-list ∩ the tools enabled for that bot; `Gateway` drops tools for every other purpose and for profiles with tool calls off. The loop is bounded (6 model rounds, 8 calls, 8k-char arguments, 16k/60k-char results, no repeated identical call); every model request still goes through `Gateway`; results are `Untrusted` data; unknown/disabled names are answered with an error and never reach the runner; each call is listed under the reply (name, ok, time, size, never the output). Tool output goes to the model's provider, so the send confirmation names the host.

## Schema v2 (additions)
`profiles`, `usage` (ledger), `memories` + `memory_fts` (FTS4, delete trigger), `chat_members`, `rounds`, `jobs`, `job_events`, `artifacts`, `skills`, `attachments`, `routines`, `routine_runs`, `bot_connectors`; new columns on `bots` (uid, profile, model, policy, project, reasoning, role_hint), `chats` (nullable bot, kind, config, summary, uid), `messages` (author, meta, kind), `tasks` (uid).

## Execution boundaries

* **PC runner** (`pc-runner/`): TLS, bearer token (stored as SHA-256), explicit workspaces (ro/rw), owner-defined commands (the phone sends a *name*, never argv), approvals with TTL, process-group cancel, write-lock per workspace or git-worktree isolation, redaction and output caps, artifact checksums, restart marks running jobs failed, idempotent `request_id`, routines (replay-safe by `(uid, scheduled)`), loopback-only inference pass-through that does not forward the phone's token, read-only MCP bridge with a tool allow-list.
* **DroidCommand**: text via `COMPANION_ASSIST` (unchanged, never executes). Execution via `COMPANION_EXEC` v1, implemented in `droidcommand-AI` (`core-security/CompanionExec.kt`, branch `feature/companion-exec-contract`): paired caller, discover/execute/receipt, request id + expiry, Allow tap in DroidCommand, existing `SecureToolExecutor`/audit, receipts. VictorBots' `DcaExecCodec` maps only `executed` to `COMPLETED`. VictorBots adds no accessibility/root/shell stack.

## Communications agents (Victorious Framework, schema v4; JVM-tested, no Android UI or service yet)

* `StyleAnalyzer`: deterministic statistics from the owner's own outgoing messages (length, case, punctuation, emoji, slang, bursts, formality, phrases). Examples never contain links, emails or number runs.
* `StyleProfiles` over `Db`: immutable versions; rollback copies an old version forward; compare; duplicate; reset; export/import without contacts or ids. Learned preferences (`shorter`, `less_formal`, `fewer_emojis`, `preserve_spelling`, `split_replies`, `prefer`, `avoid`) and approved examples are separate rows, listable and removable; `guidance()` is recomputed from what is active and is the `style` argument of droidcommand-AI's `communications.generate_reply`.
* `CommsBots`: mode (default **Approval**), profile, contacts, hours, hourly limit, pause one/all, **one bot per conversation**. Advisory only: it cannot send, grant or approve.
* `CommsCodec`: client of droidcommand-AI `COMPANION_COMMS` v1. Reuses `Jobs` (backend `DCA`); never completes a job from anything but a `completed`+`ok` report.
* Tables: `style_profiles`, `style_versions`, `style_prefs`, `style_examples`, `comm_bots`, `comm_assign`, `comm_activity`, `comm_state`.
* Model text still goes only through `Gateway` for VictorBots' own chat. Reply drafting for communications happens in droidcommand-AI (its `ReplyModel` must be `PolicyInference.ask`); VictorBots makes no model request for it.

## Known limits (deliberate)
Chat history is private app data, not separately encrypted. No background service: job polling and team rounds run while the process is alive; the PC keeps working without the phone. No webhooks. Tool calls are limited to the read-only connector loop below (`ToolLoop`); no write/exec tool is model-callable, those stay Jobs. Unattended work through DroidCommand is specified (`COMPANION_COMMS`, droidcommand-AI) but not wired: there is no Android service or connector yet, and VictorBots itself still cannot run a bot-originated job without the owner. Token counts are estimates unless the server reports usage.
