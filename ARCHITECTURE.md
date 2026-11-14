# Victorious Framework — VictorBots architecture (0.2.0)

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
2. **Keys are bound.** `SecretStore` encrypts with Keystore AES-GCM using `profileId|origin` as associated data and stores the origin beside it. Reading for another origin throws; moving ciphertext to another profile id or editing the stored origin label fails authentication. `Profiles.save` deletes the key when the origin changes. PC profiles without a token are never called.
3. **Untrusted by construction.** Other bots' messages, attachments, retrieved/generated text are wrapped by `Untrusted` and sent as user-role data; owner memory goes in the system block labelled as background. This lowers injection risk but is not the boundary: the boundary is that model output cannot change policy (`Gateway`), start work (`Jobs`: bot-originated jobs are `PROPOSED`, only the owner can approve then run), or complete work (`Jobs.applyRemote` only).
4. **Honest state.** Job states split *local* (draft/proposed/sent/unknown) from *backend-reported*. `COMPLETED` with a non-zero exit code is demoted to `FAILED`; terminal states are final; events are idempotent by sequence number; a send that may have reached the backend becomes `UNKNOWN` and is never resent (`markSubmitting` only accepts a draft).
5. **Migrations are explicit.** `Migrations.v1()` is 0.1.0's schema; `v2()` the upgrade (including the SQLite 12-step rebuild of `chats` with foreign keys off, re-enabled in `onOpen`, followed by `foreign_key_check`). Fresh installs run v1 then v2 (one path).

## Schema v2 (additions)
`profiles`, `usage` (ledger), `memories` + `memory_fts` (FTS4, delete trigger), `chat_members`, `rounds`, `jobs`, `job_events`, `artifacts`, `skills`, `attachments`, `routines`, `routine_runs`, `bot_connectors`; new columns on `bots` (uid, profile, model, policy, project, reasoning, role_hint), `chats` (nullable bot, kind, config, summary, uid), `messages` (author, meta, kind), `tasks` (uid).

## Execution boundaries

* **PC runner** (`pc-runner/`): TLS, bearer token (stored as SHA-256), explicit workspaces (ro/rw), owner-defined commands (the phone sends a *name*, never argv), approvals with TTL, process-group cancel, write-lock per workspace or git-worktree isolation, redaction and output caps, artifact checksums, restart marks running jobs failed, idempotent `request_id`, routines (replay-safe by `(uid, scheduled)`), loopback-only inference pass-through that does not forward the phone's token, read-only MCP bridge with a tool allow-list.
* **DroidCommand**: text via `COMPANION_ASSIST` (unchanged, never executes). Execution via `COMPANION_EXEC` v1, implemented in `droidcommand-AI` (`core-security/CompanionExec.kt`, branch `feature/companion-exec-contract`): paired caller, discover/execute/receipt, request id + expiry, Allow tap in DroidCommand, existing `SecureToolExecutor`/audit, receipts. VictorBots' `DcaExecCodec` maps only `executed` to `COMPLETED`. VictorBots adds no accessibility/root/shell stack.

## Known limits (deliberate)
Chat history is private app data, not separately encrypted. No background service: job polling and team rounds run while the process is alive; the PC keeps working without the phone. No webhooks. No tool-call loop: connector reads are owner-driven. DroidCommand cannot take part in unattended work. Token counts are estimates unless the server reports usage.
