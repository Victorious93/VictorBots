# VictorBots roadmap

**Everything below is PLANNED. None of it is built**, except where a section says "Already in the app" and cites the file. This document is a proposal for Don to accept, change or reject; it is not a promise and not a release schedule.

Basis: `main` at `0aa52ba` (VictorBots 0.2.0 debug build, neon theme, VictorLinux preset, README screenshots), plus a read-only look at Victorious93/droidcommand-AI `main`, Victorious93/File-Manager and Victorious93/ShizukuV on 2026-11-17. Nothing here was run on a device.

Labels used for every claim:

* **[Certain]** read directly in code, tests or docs of the named repository.
* **[Likely]** follows from what was read, not exercised end to end.
* **[Guessing]** an assumption about something not read or not decided.

## Rules this roadmap must not break

These come from `AGENTS.md`, `ARCHITECTURE.md` and `README.md`. Each planned item below is written to fit them.

* **[Certain]** Every model request goes through `Gateway` (policy first). `GatewayTest` scans sources and fails if anything else calls `Provider.chat`. Background work (routing, memory, summaries, routines, team turns) can never fall back to cloud.
* **[Certain]** A job is only *completed* by a backend report (`Jobs.applyRemote`). Never from text, a copy/open action or a queue acknowledgement; an uncertain send is never resent.
* **[Certain]** `AGENTS.md`: "Do not add accessibility/root/shell code here." DroidCommand execution is the separate `COMPANION_EXEC` contract in droidcommand-AI. This matters a lot for item 9.
* **[Certain]** The DroidCommand signer pin in `DcaBridge.DEBUG_PIN` and the hub pairing are not touched by anything below.
* **[Certain]** Provider credentials stay encrypted and out of exports and logs; a key is never reused at a newly configured endpoint.
* **[Certain]** Schema changes need explicit migrations and must preserve existing bots, chats and task data.
* **[Certain]** The four fleet apps that droidcommand-AI excludes (VictoryPed, VictorLink64, MatchVictorious, VictorOps Zombies) stay excluded. No pack, widget or integration below may target them.
* **[Certain]** Dark neon only (`#0B0F1A`, cyan `#00E5FF`, magenta `#FF2BD6`), English only, no white backgrounds. `ThemeTest` enforces the colours for code that lands.

---

## 1. Better chat: streaming, markdown, copy, retry — BUILT in the chat-polish PR (not released)

Status: implemented in the "Chat polish" pull request; see `README.md` (Chat row) and `VERIFICATION.md`. The text below is the original plan and the starting-point findings.

**Already in the app [Certain]:**

* Streaming is built. `Provider.readSse` parses server-sent events, `BotApp` writes the partial reply into the pending message as it arrives, the profile has a `streaming` switch (default on), and first-token time is measured. A **Stop** button exists (it stops *waiting locally*; the provider may still finish).
* Copy exists but is buried: long-press a message → "Use this message" → **Copy text** (`MainActivity.copy`).
* A retry exists only for one case: after a PC failure under "PC first", you can resend that one request to a cloud fallback after confirming (`retryOnFallback`).

**Not built [Certain]:** markdown rendering (replies are plain `TextView` text), a visible copy button per bubble or per code block, a general "regenerate" or "retry same profile", and edit-and-resend.

**Plan:**

1. A small markdown subset (paragraphs, bold/italic, inline code, fenced code, lists, headings) rendered with Android spans. No HTML, no images from the model, no auto-opened links. **[Likely]** a pure-Java parser keeps it JVM-testable like the rest.
2. Per-bubble **Copy**, and per-code-block **Copy**.
3. **Retry / Regenerate** on a failed or unsatisfying reply. It must go through `Gateway` as a normal `CHAT` request, show the same "To / Model / Policy" confirmation, and never silently switch destination.
4. Edit-and-resend the last user message.

**Acceptance:** parser tests (escaping, unclosed fences, 100k-character input, hostile markup); `GatewayTest` unchanged and green; instrumentation: retry on a failed reply creates exactly one new request and keeps the old one visible.
**Needs Don?** No.

## 2. Voice — PLANNED

**Not built [Certain]:** there is no microphone permission and no speech code. The manifest requests only `INTERNET` and `RECEIVE_BOOT_COMPLETED`.

**Plan:**

* **Dictation** through Android's `RecognizerIntent` (the system's own screen), so VictorBots needs no `RECORD_AUDIO` permission and receives text only, which lands in the composer for you to edit and send. **[Likely]** works on API 28.
* **Read aloud** through the platform `TextToSpeech` engine, off by default, per message.
* **[Certain, general Android behaviour]** the system recognizer is provided by another app and can send audio to its vendor's servers depending on the device. VictorBots cannot verify that. The UI must say so before first use, and voice stays off until you turn it on.
* Voice never bypasses policy: dictated text is sent like typed text, through `Gateway`, with the same confirmation.
* Not planned: wake word, always-listening, sending raw audio to any provider.

**Acceptance:** with voice off, no code path touches speech APIs; with it on, a dictated sentence appears in the composer and nothing is sent until you tap Send.
**Needs Don?** Only to confirm "system recognizer, disclosed" is acceptable. **[Guessing]** that is acceptable.

## 3. Scheduled background bots — PLANNED (partly built)

**Already in the app [Certain]:** opt-in **Routines** (`Routines`, `RoutineService`, PC runner). They are created switched off. PC routines run on the PC runner's clock, so they continue when the phone is off. Phone routines use `JobScheduler` with a 15-minute periodic job (inexact, minimum 15 minutes, enforced in `Routines.validate`) and the same policy. A run is *succeeded* only after its work finished; missed and overlapping runs are handled and tested (`RoutineAttachTest`). Background work can never use cloud under "PC first".

**Not built [Certain]:** result notifications, ready-made schedule templates ("morning brief"), and a per-routine summary screen beyond run history.

**Plan:** templates on top of the existing routines (no new scheduler), a completion notification (**[Likely]** needs the `POST_NOTIFICATIONS` runtime permission on Android 13+, requested only when you enable one), and a clear "this ran on PC / on phone" label. No exact alarms, no foreground service, no new background permission.

**Acceptance:** `RoutineAttachTest` stays green; a template creates a routine that is *off* until you switch it on; a phone routine under PC-only policy with the PC unreachable records a refusal and sends nothing.
**Needs Don?** No.

## 4. DroidCommand phone actions with an explicit OK each time — PLANNED

**Already in the app [Certain]:** `DcaExecCodec` encodes the `COMPANION_EXEC` v1 contract (`discover`, `execute`, `receipt`); a job marked "DroidCommand" is sent only after VictorBots' own pinned-signer check; the **Allow tap happens inside DroidCommand**; only a receipt with `outcome: executed` completes the job; denied/expired become *cancelled*, failed/refused become *failed*; an uncertain send becomes *outcome unknown* and is never resent.

**What the other side offers today [Certain, from `docs/COMPANION_EXEC.md` in droidcommand-AI]:** the catalogue has **one read-only capability**, `device.apps.list`. The caller cannot name a tool or pass commands. That document itself says the contract is **on disk, unverified, not compiled or run**.

**Plan:**

1. VictorBots adds nothing that executes on the phone. It only *proposes* a job from a capability DroidCommand's `discover` returns, shows the capability, arguments and expiry in a VictorBots confirmation, then hands over to DroidCommand's own Allow screen. Two taps, every time. No "remember my choice", no auto-approve, no scheduling of DroidCommand jobs (**[Certain]** the README already says DroidCommand cannot take part in unattended work).
2. Any new capability (for example "list installed apps with versions", "read battery state") is added **in droidcommand-AI**, in its `ExecCatalog`, under that repository's instructions and security docs, with its own tests.
3. Only the existing IPC (`COMPANION_EXEC`) is used. No new exported component in VictorBots.

**Acceptance:** a job never reaches COMPLETED without an `executed` receipt (`DcaExecTest` extended); expiry while waiting records *cancelled* and nothing runs; the pin check failing blocks the send.
**Needs Don?** Yes, for which capabilities DroidCommand should expose first (see PR order).

## 5. Home widget plus quick-ask — PLANNED

**Not built [Certain]:** the manifest declares no widget, no tile and no shortcut.

**Plan:**

* A **home-screen widget** showing a bot chooser and an "Ask…" button, with **no message content** on the home or lock screen.
* **Quick-ask** opens a small VictorBots screen with the composer for the chosen bot. Sending uses the normal path: `Gateway`, the "To / Model / Policy" confirmation, and for cloud profiles the existing confirm dialog. The widget itself never sends text.
* Optionally a **Quick Settings tile** (**[Likely]** available from API 24) with the same behaviour.
* Platform widgets only (`AppWidgetProvider`); no third-party libraries.

**Acceptance:** tapping the widget never causes a network request by itself; quick-ask under PC-only with an unreachable PC shows the refusal; the widget renders no chat text.
**Needs Don?** No.

## 6. Bot pack store (Don's own packs) — PLANNED

### What exists today [Certain]

* A bot is: name (≤ 80), description (≤ 240), instructions (≤ `Rules.MAX_INSTRUCTIONS`), avatar symbol (≤ 16 characters), optional avatar image file, uid, plus per-bot profile, model, policy, project, reasoning and role hint (`Migrations`, `Backup`).
* `Backup` already validates all of those fields with hard limits and has a preview, conflict handling and an idempotent restore. **[Likely]** the pack validator can reuse that code.
* **Skills** already exist (versioned instruction text that grants nothing). A pack could later include skills; this proposal keeps v1 to bots only.
* The repository `Victorious93/victorbots-packs` **does not exist yet** (checked: HTTP 404). Creating it is part of the plan and needs Don.

### Design inspiration only: the newest starred repo

The newest item in Victorious93's GitHub stars is **RLBot/RLBotPack** (https://github.com/RLBot/RLBotPack), starred 2026-11-17, license **MIT**. It is a pull-request-based collection of community Rocket League bots. **[Certain]** it is a different domain (game bots that run code), so nothing technical applies.

What it inspires, in idea only: a *single curated repository* people contribute to by pull request, with a short qualification checklist and human review before merge. **No code, files, text, names or branding are copied.** If that ever changes, MIT requires keeping its copyright and licence notice, and it would be credited in `NOTICE.md`. Packs here are plain data, never code, which RLBotPack's are not.

### Pack format (v1) — JSON, no executable code

One file per pack, `packs/<id>/pack.json`, UTF-8, at most 64 KB:

```json
{
  "schema": 1,
  "id": "fleet-droidcommand-coach",
  "name": "DroidCommand Coach",
  "description": "Helps phrase safe device requests.",
  "instructions": "You are ...",
  "symbol": "◎",
  "avatar": { "file": "avatar.png", "sha256": "<hex>" },
  "version": "1.0.0",
  "author": "Victorious93",
  "license": "<SPDX id chosen by Don>",
  "parent": { "id": "…", "version": "…", "sha256": "…" },
  "sha256": "<hex>"
}
```

* `avatar` and `parent` are optional (`null` or absent). The avatar is a PNG of at most 256 KB, decoded and size-checked; anything else is refused.
* `sha256` is the digest of the pack's canonical JSON (sorted keys, no whitespace, `sha256` field omitted). The repository's `index.json` also lists the digest of the exact file bytes. **Both** must match before anything is shown as installable. **[Certain]** this proves integrity, not authorship; authorship rests on HTTPS to the repository and on Don's review of every merge. Signing packs is a possible later step. **[Guessing]** it is not needed for a first-party-only store.
* A pack **cannot** set a provider, a model, a policy, tools, connectors, memory, jobs or routines. Installed bots inherit the workspace default provider and your policy. Unknown fields are rejected, not ignored.

### Install: HTTPS with a review screen

1. Fetch `index.json` and `pack.json` over HTTPS only, from a fixed allow-listed repository path pinned to a tag or commit (same host checks as `Hosts.base`; no redirects off-host; bounded size). **[Certain]** this request carries no model text, so like the VictorLinux capability probe it does not go through `Gateway`; it must still be explicit and owner-initiated.
2. Show a **review screen**: name, author, license, version, lineage, the digests, and the **full instructions text**. State plainly: "This installs text instructions only. It gives the bot no tools and no permissions. Your policy still applies."
3. Install only after you tap Install. Pack instructions become the bot's own instructions (the highest-trust text in its prompt), which is exactly why you read them in full first.
4. Needs an explicit schema migration (planned **v3**): store `pack_id`, `pack_version`, `pack_sha256` and the parent reference on `bots`; extend `Backup` to export and import them (reading v1 and v2 backups unchanged).

### In-app: Clone, Edit, Build-on, Publish back as a PR

* **Clone** makes an independent local copy (new uid) and records where it came from.
* **Edit** changes a local bot; if it came from a pack, the edit is marked "modified" and the original digest is kept for comparison.
* **Build-on** creates a new bot whose `parent` points at the pack and version it was built on. The parent reference is kept on export and publish.
* **Publish back as a PR** exports the pack JSON and opens GitHub's web editor for `Victorious93/victorbots-packs` in the browser, so GitHub offers a fork and a pull request. **VictorBots stores no GitHub token.** **[Likely]** GitHub's web flow forks automatically for people without write access. A direct API publish with a stored token is deliberately not proposed.

**Acceptance:** validator tests (oversized, unknown fields, bad digest, bad avatar, hostile Unicode, duplicate id); migration test from a real v2 database with data preserved; install of a tampered pack is refused; no pack field can change policy or profile (negative tests); `Gateway` untouched.
**Needs Don?** Yes: create the repository, choose the pack licence, approve the schema.

### Proposed starter set (original, first-party, to be written)

Each is a *text-only* helper. None claims it can act on a device or a PC. None targets an excluded fleet app. Only the facts verified in this review are used (DroidCommand's consent model, VictorFiles' features, VictorLinux as a PC profile, ShizukuV's description).

| Pack id | What it helps with |
|---|---|
| `fleet-droidcommand-coach` | Phrasing requests for DroidCommand AI, explaining that its Allow screen decides, never claiming something ran. |
| `fleet-victorfiles-organizer` | Planning folder clean-ups and archive tasks for VictorFiles; produces a plan you carry out. |
| `fleet-victorlinux-admin` | Explaining steps for a VictorLinux PC; commands are for you to read and run, never claimed as executed. |
| `fleet-shizukuv-explainer` | Explaining what ADB-level access through ShizukuV can and cannot do, and the risks. |
| `fleet-release-notes` | Drafting release notes and checklists from a diff or commit list you paste. |
| `fleet-readme-polisher` | Applying the fleet README standard to a README you paste. |
| `fleet-android-crash-triage` | Reading a pasted crash log and listing hypotheses versus evidence. |
| `fleet-pack-author` | Helping write a new pack in this format. |

**[Guessing]** the instruction texts themselves do not exist yet; they would be written new, reviewed by Don, and kept under whatever licence Don picks.

## 7. Fleet apps: per-bot folders and safe intents — PLANNED

**Already in the app [Certain]:** attachments come from `ACTION_OPEN_DOCUMENT` with size and type limits; saving an artifact uses `ACTION_CREATE_DOCUMENT`; backups export through the document picker. There is no folder tree access and no per-bot folder today.

**Verified about VictorFiles (Victorious93/File-Manager, application id `ai.victorious.filemanager`):**

* **[Certain]** It is a standalone file manager; its README describes browsing, archives, editors, and root/Shizuku tools.
* **[Certain]** Its manifest exports an `ACTION_SEND`/`SEND_MULTIPLE` (`*/*`) receiver and `VIEW` filters for some media types, and a `FileProvider`.
* **[Certain]** Its "fleet bridge" (external control) is **off by default and accepts only DroidCommand AI's packages**. VictorBots cannot use it directly and this roadmap does not ask it to.
* **[Certain]** DroidCommand's inbound catalogue lists File-Manager as a `CODE_ASSIST` entry (`ai.victorious.filemanager`).

**Plan:**

1. **Per-bot folders.** You pick one folder once with `ACTION_OPEN_DOCUMENT_TREE` (for example `Documents/VictorBots`); VictorBots keeps the persisted URI grant and creates one subfolder per bot (`<bot-name>-<short-uid>`). Bot-produced files and chat exports are written only there. Reading a file *into* a chat still goes through the existing attachment picker and limits, so the bot never gets silent read access.
2. **[Likely]** VictorFiles can browse that folder like any other storage on the phone, once VictorFiles itself has storage access. That is unverified; no VictorFiles change is required or proposed.
3. **Other fleet intents:** a "Share to…" action using the standard `ACTION_SEND` chooser, so VictorFiles can appear as a target (**[Likely]** it will, given its manifest). No other fleet app's intents were verified, so none are proposed. Other apps are added to this list only after their manifests and docs are read.
4. **[Certain]** the minimum is Android 9 (API 28), where tree access works through the Storage Access Framework; no `MANAGE_EXTERNAL_STORAGE`, no broad storage permission.

**Acceptance:** with no folder chosen nothing is written; revoking the grant in Android settings is detected and the UI says so; a bot name with path separators cannot escape its folder (tests on the name-to-folder function); backup export never includes the tree URI.
**Needs Don?** No.

## 8. Per-bot chat models — PLANNED (part a mostly built, part b needs a contract)

### (a) Each bot picks its own provider, with a workspace default — largely built

**[Certain]** Already in the app: profiles of three kinds (cloud HTTPS, PC HTTPS including the optional VictorLinux preset, and DroidCommand), a workspace default profile, and per-bot profile **and** model (`bots.profile`, `bots.model`, "Edit bot (provider, model, policy)"). The bots list shows the effective provider and model (see the first screenshot in the README). Policy can only get stricter per bot.

**Planned:** small UX gaps only: a one-tap "use a different provider for this chat" that does not edit the bot, and clearer wording when a bot inherits the default. No schema change expected.

**BUILT in the per-chat provider PR (not released, never run on a device):** `ChatProvider` and the chat header's **⇄** picker. It needed one additive column after all (`chats.profile`, schema v5) so the choice persists per chat. Policy tests are unchanged and `ChatProviderTest` checks that the switch never edits the bot.

### (b) DroidCommand bots choose a specific model — NOT possible today

**Finding [Certain]** (read from `core-security/.../InboundEntryPoints.kt` and `CompanionAssistActivity.kt` in droidcommand-AI `main`, plus `DcaBridge.java` here):

* A `COMPANION_ASSIST` v1 **request** can carry only `v`, `task` (`assist` or `pair`), `capability`, `prompt` (≤ 60,000 characters), `context`, `instruction`, `destructive`, and the pairing proof. There is **no model field**.
* The **reply** carries only `v`, `ok` and either `text` or `error`. It cannot list models and does not say which model answered.
* DroidCommand picks the model itself: its own policy router if one is set, otherwise the provider or local model **its chat screen last used**. The caller has no say.
* **[Certain]** DroidCommand parses with `ignoreUnknownKeys = true`, so a `model` key sent today would be **silently ignored**. VictorBots must not add one and imply it was honoured. Today VictorBots correctly shows "model not set (default)" for DroidCommand bots.
* `COMPANION_EXEC`'s `discover` returns executable capabilities, not models.

**Minimal hub-side contract droidcommand-AI would need (proposal, additive, v1 stays valid) — PLANNED, in the droidcommand-AI repo:**

1. New task `models`: request `{ "v": 1, "task": "models" }`. Same caller verification as `assist`. Reply `{ "v": 1, "ok": true, "default": "<id>", "models": [ { "id": "<opaque id>", "label": "<display name>", "where": "device" | "cloud" } ] }`. No keys, no endpoints, no provider secrets. Whether this needs an Allow tap is DroidCommand's call; **[Likely]** a read of names only could be tap-free, but it does reveal which providers you use, so asking is the safer default.
2. `assist` gets an optional `model` string (≤ 160). DroidCommand must show the chosen model on its consent screen. The reply gains `model_used` (what actually answered) so VictorBots can display it truthfully. An unknown id returns `ok: false, error: "unknown_model"`; DroidCommand never silently substitutes a *different cloud destination*.
3. **Fallback in VictorBots:** if the bot has no model, or `models` is unsupported (an older DroidCommand answers with a cancel or ignores the task), or the reply lacks `model_used`, VictorBots omits `model`, uses DroidCommand's default, and says "DroidCommand chose the model."

That change belongs in `Victorious93/droidcommand-AI`, must follow its instructions and security docs, and must keep the four excluded apps excluded. VictorBots makes no change until it exists and is tested there.

**Acceptance (VictorBots side):** old DroidCommand (no `models` support) → picker hidden, chat unchanged; new DroidCommand → picker lists returned models, the chosen id is sent, `model_used` is shown, mismatch is displayed not hidden; the signer pin check still runs first.
**Needs Don?** Yes (approve the contract; see the questions).

## 9. Capability tiers (non-root, Shizuku, root) — PLANNED

### Who owns what [Certain]

* droidcommand-AI **already has** Shizuku and root support: a Shizuku host and privileged shell, a `ShizukuToolboxBackend` (shell user) and a `RootToolboxBackend` (`su -c`), a `ShellTool` and `RootTool` behind its `SecureToolExecutor` with approval and an `AuditLog`. Root is detected only by looking for an `su` binary; nothing calls `su` until you confirm a command.
* VictorBots has **none** of that, and `AGENTS.md` says not to add accessibility/root/shell code here. The README also says "No third-party runtime libraries".
* **ShizukuV** (https://github.com/Victorious93/ShizukuV, application id `com.victorious.shizukuv`) is a fork of RikkaApps/Shizuku. **[Certain]** per its README: the Shizuku manager code is **Apache-2.0** and the vendored Shizuku-API is **MIT**. Note that the API is MIT, not Apache-2.0: ShizukuV's own `api/UPSTREAM.md` and the upstream RikkaApps/Shizuku-API repository both say MIT. Both licences would need crediting (in `NOTICE.md`) if any of this were ever used here.

### Recommendation

**VictorBots does not execute anything privileged. DroidCommand does.** VictorBots shows the tiers, proposes actions, and routes them through the existing `COMPANION_EXEC` IPC. This keeps `AGENTS.md`, the "no third-party libraries" claim and the one-place-for-execution design intact. Direct use of the Shizuku API inside VictorBots is possible but is a policy change for Don (question 1).

### The tiers

| Tier | State | Default | How it is detected |
|---|---|---|---|
| 0 Non-root | Required, always on | Yes | n/a |
| 1 Shizuku / ShizukuV | Optional, ADB-level | Off | **[Planned]** DroidCommand's `discover` reports whether Shizuku is running; if absent or unknown, VictorBots shows "not available" and nothing else changes |
| 2 Root | Optional, `su` | Off | Same; **[Planned]** DroidCommand reports "su present / absent". Nothing is requested until you grant it, per action |

All tiers are opt-in, detected at runtime and degrade gracefully: if a tier disappears (Shizuku stops, root revoked), actions that need it show "unavailable" and stay un-run; the app and all lower tiers keep working. **[Certain]** `COMPANION_EXEC` v1 today reports no tier information, so the detection above needs a small additive field in droidcommand-AI.

### Proposed features per tier (all PLANNED)

**Tier 0, non-root:** everything in this roadmap that needs no privileges (items 1–8); DroidCommand's own non-privileged read-only capabilities (starting with `device.apps.list`).

**Tier 1, Shizuku or ShizukuV (DroidCommand executes):**

* Read-only diagnostics for the APK-crash-diagnosis skill: bounded `logcat` capture, package info, `dumpsys` summaries.
* Install an APK **you** selected from a job artifact whose SHA-256 you checked.
* Grant or revoke a runtime permission for a named package.
* Each is a **named capability with an argument allow-list** in DroidCommand's catalogue. The model never supplies a raw command line.

**Tier 2, root (DroidCommand executes):** the same named capabilities plus things Shizuku cannot do, only after an explicit grant and per-action approval. No arbitrary `su -c` from model text. **[Guessing]** a short initial list; Don should pick it.

### Per-action OK, audit and the policy gateway

1. A bot may only **propose** (`PROPOSED` job). Only you can approve, as today.
2. **OK screen 1, in VictorBots:** exact operation, capability id, every argument, target package/path, tier required, "this can change your phone", and an expiry. Nothing is sent until you confirm.
3. **OK screen 2, in DroidCommand:** its existing Allow screen. Two independent approvals for every privileged action; no standing grants; nothing scheduled or automatic.
4. **Audit:** VictorBots writes an entry (capability, args hash, tier, decision, receipt outcome) to a local, bounded, redacted audit log. DroidCommand writes its own `AuditLog` entry. A receipt other than `executed` is never shown as success.
5. **"Through the policy gateway":** `Gateway` handles model text, so privileged actions would pass through a new sibling choke point, `ActionGate`, driven by the same owner settings in `Policy` (tier switches, per-action allow). **[Likely]** that is what is intended. A source-scan test, like `GatewayTest`, would fail if anything other than `ActionGate` calls the `COMPANION_EXEC` sender. **[Guessing]** Don may want a different shape; this is open for review.

### What DroidCommand owns versus VictorBots

| Concern | DroidCommand | VictorBots |
|---|---|---|
| Running any privileged command | Yes | **Never** |
| Detecting Shizuku/root | Yes (reports it) | Displays what is reported |
| Capability catalogue and argument allow-lists | Yes | Reads `discover`; adds none |
| Allow screen and its audit log | Yes | No |
| Proposal, first OK screen, local audit entry, job record | No | Yes |
| Treating a reply as proof | No | Never; only an `executed` receipt completes a job |

**Acceptance:** `ActionGate` source-scan test; with Shizuku and root both absent every privileged proposal shows "unavailable" and sends nothing; a tier switch off by default; a receipt `denied`/`expired` never reads as success; the pin check runs before every send.
**Needs Don?** Yes, first and foremost for question 1.

## 10. Recommended PR order (small, shippable, each with an acceptance test)

Each is its own PR. Items marked **[Don]** need his decision before work starts.

| # | PR | Acceptance test | Don? |
|---|---|---|---|
| 1 | Chat polish: markdown subset, per-bubble copy, retry/regenerate, edit-and-resend | Parser tests; `GatewayTest` green; retry creates exactly one request | No |
| 2 | Per-chat provider switch and inheritance wording (item 8a) | Policy tests unchanged; switch never edits the bot | No |
| 3 | Pack format, validator, local Clone/Edit/Build-on, schema v3 and backup v3 | Validator tests; v2→v3 migration with data kept; no pack field can change policy | **[Don]** schema |
| 4 | Create `Victorious93/victorbots-packs`, CI validator, 3 starter packs | CI rejects a bad pack; packs contain no executable content | **[Don]** repo, licence, packs |
| 5 | Pack install over HTTPS with the review screen; publish-as-PR browser hand-off | Tampered pack refused; nothing installs without the Install tap; no token stored | No (after 4) |
| 6 | (droidcommand-AI) COMPANION_ASSIST `models` task and optional `model` / `model_used` | That repo's JVM tests incl. old-client compatibility; excluded apps still refused | **[Don]** contract |
| 7 | VictorBots DroidCommand model picker with default fallback | Old DroidCommand → picker hidden; `model_used` shown | No (after 6) |
| 8 | Per-bot SAF folders, folder-safe names, "Share to…" | Name-escape tests; revoked grant detected; backup excludes URIs | No |
| 9 | Widget and quick-ask (and optional tile) | Widget never sends; quick-ask obeys policy and confirmation | No |
| 10 | Voice: system dictation and read-aloud, off by default | No speech API touched while off | Minor [Don] |
| 11 | Routine templates and completion notifications | `RoutineAttachTest` green; templates start switched off | No |
| 12 | Tier display and `ActionGate` with no privileged execution; (droidcommand-AI) `discover` reports tiers | Source-scan test; absent tiers → "unavailable", nothing sent | **[Don]** question 1 |
| 13 | (droidcommand-AI) first privileged named capabilities; VictorBots proposal flow with two OK screens and audit entries | Receipt mapping; denied/expired never success; audit entries written | **[Don]** capability list |

Items 6, 12 (DroidCommand part) and 13 are changes to **droidcommand-AI**, which has its own repository, instructions and security docs; VictorBots' side waits for them.

## 11. Top 3 open questions for Don

1. **Who runs privileged actions (Shizuku/ShizukuV/root)?** (A, recommended) DroidCommand only; VictorBots proposes, displays tiers and keeps the audit trail, with no Shizuku or `su` code in VictorBots. (B) VictorBots integrates the Shizuku API itself (needs `AGENTS.md` and the "no third-party libraries" claim changed, adds an MIT dependency to credit). (C) No privileged tiers for now.
2. **How should DroidCommand bots pick a model?** (A, recommended) Add the additive `models` task and optional `model` / `model_used` to `COMPANION_ASSIST` in droidcommand-AI, with VictorBots falling back to DroidCommand's default. (B) Leave it as is; VictorBots only says "DroidCommand chooses the model". (C) Carry model choice through `COMPANION_EXEC` instead.
3. **Where do bot packs live, and under what licence?** (A, recommended) A new public `Victorious93/victorbots-packs` repo, pinned-tag HTTPS install, pull-request publishing through the browser, and an MIT licence for the packs (your call on the licence). (B) Bundle the starter packs inside the APK only, no network install yet. (C) Defer packs and do chat polish, folders and widget first.
