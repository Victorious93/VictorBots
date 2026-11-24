# VictorBots development rules

Preserve existing bots, chats and task data. Add explicit database migrations when schema changes. Use the existing DroidCommand contract and permission boundaries; never label a task as executed from a text reply or a copy/open action. Keep provider credentials encrypted and out of exports/logs. Never silently reuse a key at a newly configured endpoint. No public release with the bundled debug key.

Read README.md, ARCHITECTURE.md and VERIFICATION.md first. Build with scripts/build-sdk.sh, run the pure Java validation checks and the isolated-emulator instrumentation tests appropriate to the change. Do not claim live-provider or DroidCommand integration unless actually tested. Any DCA integration change belongs in the droidcommand-AI repo (Victorious93/droidcommand-AI), must follow that repository's instructions, read its security documentation and keep the four excluded fleet apps excluded.

## 0.2.0 additions (read before changing these areas)
* Every model request goes through `Gateway` (policy first). `GatewayTest` scans the sources and fails if anything else calls `Provider.chat`; do not add a second path. Background work (routing, memory, summaries, routines, team turns) must never be allowed to fall back to cloud.
* Job completion may only come from `Jobs.applyRemote` (backend report). Never mark a job done from text, a copy/open action or a queue acknowledgement; never auto-resend an uncertain send.
* `pc-runner/` is the PC backend. Its tests (`test_runner.py`) and `tests/jvm/RunnerTest.java` must stay green. Keep the phone unable to pass argv or paths outside workspaces.
* Run `bash scripts/test-jvm.sh` and the instrumentation tests (isolated emulator only) before claiming a change works. Record what was and was not run in `VERIFICATION.md`.
* DroidCommand execution is the separate `COMPANION_EXEC` contract in droidcommand-AI (`docs/COMPANION_EXEC.md`). Do not add accessibility/root/shell code here.

## 0.4.0 additions: communications agents (read before changing these areas)
* `StyleAnalyzer` receives only the OWNER'S OWN outgoing messages. Never pass incoming text, and never let unapproved AI text teach a style: `StyleProfiles.record` learns nothing from an approved, rejected or regenerated draft, and from an edit only if the owner allowed it or saved it. `StyleTest` guards this.
* A new communications bot starts in Approval mode. `CommsBots` checks are advisory; the authority to send is an owner-created grant in droidcommand-AI plus a VictorSMS rule. Never add a way for VictorBots to approve, grant or auto-run a send, and never create a bot-originated job that skips `PROPOSED`.
* `CommsClient` makes one send per call and never retries; every send gets a fresh nonce. Keep `not_sent` (nothing left the app) and `no_reply` (it may have run) apart: only the first may return a job to a draft. `MessengerComms` must verify the pinned certificate before binding and accept a reply only from droidcommand-AI's uid. Do not add an exported component or an inbound service to VictorBots for this contract.
* `CommsCodec` maps only what droidcommand-AI reports, through `Jobs.applyRemote`. Only `completed` with `ok:true` completes a job; `unknown` becomes "outcome unknown" and is never resent. The contract lives in droidcommand-AI (`docs/COMPANION_COMMS.md`); change it there first.
* Schema v4 is additive. Style/communication tables are NOT in backup v2 yet (profiles have their own export/import, which carries no contacts or ids).

