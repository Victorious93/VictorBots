# VictorBots changelog — Victorious Framework

## Unreleased (schema v4)

### Added
- `StyleAnalyzer`, `StyleProfiles`, `CommsBots`, `CommsCodec` and migration v4 (additive). 150+ new JVM checks; full suite green.
- AGENTS.md rules for the communications area.
- `CommsClient` + `MessengerComms`: an outgoing COMPANION_COMMS client (certificate pinned before binding, reply accepted only from droidcommand-AI's uid, one send per call, never a retry) and More → DroidCommand → *Check messaging connection*. `not_sent` (job back to a draft) is kept apart from `no_reply` (outcome unknown, never re-sent). 43 new JVM checks. **No manifest change; never run on a device.**

### Not done
- Screens for profiles and bots, any bot or job flow that uses the connection, voice, calls, and backup-v2 coverage of the new tables.
