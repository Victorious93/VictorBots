# Victorious Framework — VictorBots architecture

`MainActivity` owns native Android screens and UI state. `Store` owns SQLite schema/version 1 with foreign-key cascade deletion for bots, chats and messages. `BotApp` runs direct-provider calls on one application-scoped executor, persists results, and refreshes the foreground activity. Pending completions use conditional updates so late responses cannot overwrite cancelled or completed messages. Interrupted direct requests are marked failed on process restart and are not retried.

`Rules` contains SDK-independent endpoint, certificate, length and context-budget validation. `Provider` performs bounded HTTPS POST requests to an explicitly supplied OpenAI-compatible endpoint; it never executes returned tool calls. `SecretStore` encrypts provider keys with an Android Keystore AES-GCM key. `DcaBridge` targets the inspected DroidCommand CompanionAssistActivity explicitly and verifies the installed app's current signing certificate before sending and after receiving a result.

DroidCommand requests persist their pending message ID across activity recreation. The existing contract has no request nonce or capability-negotiation handshake, so VictorBots permits one outstanding DroidCommand request at a time. It never labels package presence as a verified live connection. The connection page separately reports certificate match and whether a valid reply was received previously.

Task handoff is intentionally a visible user action. There is no hidden service, shell execution, accessibility service, root privilege, remote API listener, automatic message sending, or background scheduler in VictorBots 0.1.0. Real execution integration must reuse DroidCommand's policy/execution boundary when an appropriate inbound contract is available; it must not silently turn this text-assist interface into an execution interface.

## Follow-on work

1. Review/build/install the DroidCommand registration change from the droidcommand-AI repo (Victorious93/droidcommand-AI), which owns hub pairing / COMPANION_ASSIST registration, and verify live cloud/local replies on the owner's actual device.
2. Establish private release signing, update the trusted DroidCommand fingerprint and pair the new VictorBots signer.
3. Add a separately authorized execution contract in DroidCommand with capability discovery, request IDs, scoped grants, approval UI, cancellation and real result receipts; only then implement automatic task dispatch.
4. Add Room migrations or retained native SQLite migrations, virtualized long conversation rendering, optional encrypted-history storage, and export/import schema evolution before scaling to large archives.
5. Add background routines, voice and cross-device transport only against implemented backend capabilities. Do not describe these as complete until exercised.
