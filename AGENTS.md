# VictorBots development rules

Preserve existing bots, chats and task data. Add explicit database migrations when schema changes. Use the existing DroidCommand contract and permission boundaries; never label a task as executed from a text reply or a copy/open action. Keep provider credentials encrypted and out of exports/logs. Never silently reuse a key at a newly configured endpoint. No public release with the bundled debug key.

Read README.md, ARCHITECTURE.md and VERIFICATION.md first. Build with scripts/build-sdk.sh, run the pure Java validation checks and the isolated-emulator instrumentation tests appropriate to the change. Do not claim live-provider or DroidCommand integration unless actually tested. Any DCA integration change belongs in the droidcommand-AI repo (Victorious93/droidcommand-AI), must follow that repository's instructions, read its security documentation and keep the four excluded fleet apps excluded.

## 0.2.0 additions (read before changing these areas)
* Every model request goes through `Gateway` (policy first). `GatewayTest` scans the sources and fails if anything else calls `Provider.chat`; do not add a second path. Background work (routing, memory, summaries, routines, team turns) must never be allowed to fall back to cloud.
* Job completion may only come from `Jobs.applyRemote` (backend report). Never mark a job done from text, a copy/open action or a queue acknowledgement; never auto-resend an uncertain send.
* `pc-runner/` is the PC backend. Its tests (`test_runner.py`) and `tests/jvm/RunnerTest.java` must stay green. Keep the phone unable to pass argv or paths outside workspaces.
* Run `bash scripts/test-jvm.sh` and the instrumentation tests (isolated emulator only) before claiming a change works. Record what was and was not run in `VERIFICATION.md`.
* DroidCommand execution is the separate `COMPANION_EXEC` contract in droidcommand-AI (`docs/COMPANION_EXEC.md`). Do not add accessibility/root/shell code here.
