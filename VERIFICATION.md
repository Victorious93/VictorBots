# Victorious Framework — VictorBots verification

## 0.2.0 development build — 2026-11-14

**Build environment:** Linux container, JDK 21, Android SDK platform 35 / build-tools 35.0.0 (`scripts/build-sdk.sh`), Python 3.13, Android emulator API 28 x86_64 **software-emulated (no KVM)**. No physical phone. No real cloud-provider credentials. No real Ollama/llama.cpp server. No DroidCommand APK installed.

### Artifact (built by `scripts/build-sdk.sh`, not by Gradle)
* Path: `app/build/sdk/VictorBots-0.2.0-debug.apk` (not committed; `*.apk` is ignored)
* Package `com.victorious.bots`, versionName `0.2.0`, versionCode `2`, minSdk 28, targetSdk 35
* Signed with the **public development key** (`signing/victorbots-debug.jks`), APK signature scheme v3, signer SHA-256 `23cefc35fa9b8ce1f5d3cce7b189aa340ff65051834cc8996eada08d6a0f5b82` (identical to 0.1.0, so it updates 0.1.0 in place). **This is not a production-signed release.**
* APK SHA-256 of the reviewed build: `8a8b0159841495d392f8d4a9bb9e5d4789c8daa68d52b4e62fe4160f878d7170` (172,509 bytes). The script is not byte-reproducible across runs, so a rebuild will have a different checksum; the signer fingerprint above is the stable identity.

### Automated checks actually run (all passed)
| Suite | Where it ran | Checks |
|---|---|---|
| `MigrationTest` | JVM, real SQLite | 21 — 0.1.0 database upgraded with data preserved, cascades intact after the `chats` rebuild, rollback on failure, FTS4 |
| `PolicyTest` | JVM | 189 — full purpose × mode × destination matrix, endpoint/origin rules, redaction, token budget |
| `ProviderTest` | JVM, **real TLS server** | 42 — pinning accepted/rejected, unpinned self-signed rejected, redirects refused and key not sent to target, streaming + usage + first-token, cancel, every error category, secrets absent from messages, images refused unless enabled |
| `GatewayTest` | JVM | 145 — no cloud call under PC-only/PC-first for any purpose, bot can only tighten policy, injected text cannot change policy, key never sent to another endpoint, PC without token refused, ledger, structural scan that nothing bypasses the gateway |
| `DiagnosticsTest` | JVM, real TLS | 46 — five separate states, policy blocks without any request, no secret in any text, cost/usage labelling |
| `MemoryTest` | JVM | 54 — scoping, project sharing opt-in, inert suggestions, budget, provenance, untrusted wrapping, policy-gated extraction, cascade/FTS regression found on device |
| `TeamTest` | JVM | 59 — mentions, routing with/without policy, turn/token/time limits, stop, failure isolation, duplicates, hop limit, private-memory isolation, review workflow, restart recovery |
| `RunnerTest` | JVM client ↔ **real Python runner over real TLS** | 61 — auth, path/symlink/secret-file refusal, redaction, artifact checksum, idempotent request ids, cancel, write serialisation, approval wait/deny/expiry, runner restart, uncertain outcome reconcile |
| `BackupTest` | JVM | 61 — round trip, no secrets, idempotent restore, conflicts, 13 hostile/malformed inputs, v1 backups |
| `RoutineAttachTest` | JVM | 56 — Java schedule == runner schedule (golden values from the runner, incl. DST), missed/overlap/replay, attachments, skills grant nothing |
| `DcaExecTest` | JVM | 20 — receipt mapping: only `executed` completes |
| `pc-runner/test_runner.py` | Python | 15 run (1 skipped live check) — DST schedules, routine replay/missed/overlap/approval, worktree isolation, proxy does not forward the token, MCP client + read-only allow-list **against a local fixture server** |
| `SmokeInstrumentation` | **API 28 emulator** | 108 — see below |

JVM total: 754 checks, 0 failed (`bash scripts/test-jvm.sh`).

### On-device (API 28 x86_64 emulator, isolated)
108 checks passed, including: a genuine 0.1.0 database upgraded (messages not lost in the table rebuild, foreign keys on afterwards); Keystore key bound to profile+origin (wrong origin refused; ciphertext moved to another profile id or with an edited origin label fails authentication; never plaintext); 0.1.0 shared key migrated into a bound profile; backup round trip on Android SQLite; policy gateway and a team round on Android SQLite; job state machine; FTS4 search; every screen opened by clicking through the UI.

**Live flow (emulator → real PC runner on the build host over the emulator's network, `10.0.2.2`, real TLS with a pinned self-signed certificate):** reachable/auth/model-listed; streamed reply with measured first-token time (197 ms of 506 ms), requested vs server-reported model, tokens as reported; wrong pin and unpinned self-signed rejected by Android's TLS stack; a job completed by the runner's own report; an approval-required command held until approved and run only afterwards; a long job cancelled; PC-only policy allowing the PC bot. *The model behind the runner was a stub server (`tests/live/fixture.py`), not a real model, and the "PC" was the build host, not a phone-reachable LAN machine.*

### Bugs the tests found and that were fixed
Regex redaction leaked a token after `Authorization: Bearer`; diagnostics overwrote a policy-blocked state; team prompts contained the owner message twice; backup import silently skipped an invalid routine and duplicated bots with non-hex ids on re-import; runner raised `KeyError` on a malformed schedule; **orphaned search-index rows crashed memory insertion after a bot was deleted (found only on the device)**.

### NOT verified / blockers (do not read these as passed)
* **Real MCP server:** `pc-runner` can bridge the official `@modelcontextprotocol/server-filesystem@2.3.0`, but running it needs `npx` to download the package and the session's permission layer refused that. The runner's MCP client and read-only allow-list were tested only against a local fixture. Run `VB_REAL_MCP=1 python3 pc-runner/test_runner.py Mcp` yourself.
* **Real provider behaviour:** no cloud provider was called; no real Ollama or llama.cpp. OpenAI's documentation host was unreachable, so OpenAI-specific parameters were not checked against it. Ollama's documented `stream_options`, `tools`, `reasoning_effort`, `/api/show` capabilities and `*.context_length` were read and used.
* **DroidCommand `COMPANION_EXEC`:** implemented in `droidcommand-AI` (separate branch). See that repository's audit entry for exactly what compiled/ran. The VictorBots side was tested against its wire format only; **no DroidCommand APK was installed, so no live DroidCommand→VictorBots execution was observed.** The existing `COMPANION_ASSIST` live reply path is still unverified, as in 0.1.0.
* **systemd install path** of `pc-runner/install-ubuntu.sh` (no systemd in the container). The non-systemd install, certificate, token hash and TLS/auth behaviour were run.
* **Physical device, Doze/background behaviour of phone routines, Android 10–15**, the JobScheduler run of `RoutineService`, screen rotation, and image attachments to a real vision model were not exercised.
* **Gradle** (`./gradlew :app:assembleDebug`) was not run for VictorBots (AGP unresolved in the environment).
* Visual design was not reviewed beyond "it draws and navigates".

## 0.1.0 history (kept)


## DroidCommand integration update — 2026-11-08

- Verified the existing VictorBots 0.1.0 APK signature and package name with Android SDK tools.
- The refreshed DroidCommand registration change, which lives in and is owned by the droidcommand-AI repo (Victorious93/droidcommand-AI), was applied to a clean checkout of DroidCommand commit `6c8c0e8cf50085443458533b22273bd154b41e86`.
- Built and signed `DroidCommand-AI-VictorBots-debug.apk` with JDK 21, Android SDK 36, NDK r28c and CMake 3.22.1.
- Ran `:app:assembleDebug`, `:app:testDebugUnitTest`, `:core-security:ktlintCheck` and `:core-security:test` with `includeAndroid=true`. Gradle succeeded. All 305 security tests and 221 Android unit tests passed.
- Verified the DroidCommand debug signer against VictorBots' configured default pin.
- DroidCommand APK SHA-256: `61fab844164d0cb9ab3433c0f2259f44dcb8cc5f7d19898ca44fd9ad3fed3f54`.
- Installed both APKs on the Android 9 emulator.
- Validated the manifest XML and VictorBots package visibility. The emulator repeatedly displayed a System UI not responding dialog before a paired request could complete. Live replies remain unverified.
- The GitHub connection rejected creation of the branch and commit with HTTP 403.

## Original VictorBots APK checks — 2026-10-31

Build: 0.1.0 debug. The refreshed droidcommand-AI registration change leaves this APK unchanged.

## Executed successfully

- Compiled all application Java sources against Android SDK platform 35 with JDK 17.
- Compiled Android resources, converted bytecode with D8, aligned, and signed an installable APK using official Android SDK build tools 35.0.0.
- Verified the APK signature with `apksigner verify --verbose --print-certs`; the certificate matches the packaged development key.
- **19 pure-Java checks passed**: endpoint restrictions, malformed/credential-bearing URLs, certificate formatting, input limits, context-budget bounds and latest-message preservation.
- Installed the APK and test APK on an Android 9 / API 28 x86_64 emulator.
- **23 Android integration checks passed**: starter profiles, database persistence, pending/completed/cancelled message behavior, duplicate/late reply protection, Android Keystore secret roundtrip/removal, no plaintext key in storage/export, explicit DCA component and v1 payload, result parsing, invalid-reply refusal, missing-hub refusal, and cascaded deletion.
- Launched `com.victorious.bots/.MainActivity` on that emulator (`am start -W`: Status ok). Inspected the rendered home and chat screens; the crash log was empty at initial screen capture.
- `git apply --check` accepted the DroidCommand registration change against the retrieved source files. That registration lives in the droidcommand-AI repo (Victorious93/droidcommand-AI) and is owned there.
- Generated a Gradle wrapper and included the downloaded Gradle distribution's SHA-256 in its properties.

## Not verified / not implemented

- No real cloud-provider credentials were used; live model responses, billing and provider-specific behavior remain unverified.
- No installed DroidCommand build was updated in this session. The droidcommand-AI registration change was not compiled in its full repository, merged, or pushed. The DCA-to-VictorBots live reply path remains unverified.
- No physical phone was connected. Android versions above API 28 were compile-targeted but not runtime-tested here.
- The Gradle APK build failed because its environment could not resolve the Android Gradle Plugin artifact. The delivered APK was built successfully with `scripts/build-sdk.sh` instead. Do not describe the Gradle app build as passed.
- Automated device execution, background routines, voice, cross-device networking, and autonomous bot delegation are not implemented. Task status is manual, and task handoff only copies text and opens DroidCommand.
- JSON export is implemented and serializer/key-exclusion checked; the interactive Android document-picker export and avatar-picker flow were not exercised end to end.
- The APK uses a reproducible public development key. A production release needs a separate private key and a verified corresponding DCA registration.
