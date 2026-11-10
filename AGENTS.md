# VictorBots development rules

Preserve existing bots, chats and task data. Add explicit database migrations when schema changes. Use the existing DroidCommand contract and permission boundaries; never label a task as executed from a text reply or a copy/open action. Keep provider credentials encrypted and out of exports/logs. Never silently reuse a key at a newly configured endpoint. No public release with the bundled debug key.

Read README.md, ARCHITECTURE.md and VERIFICATION.md first. Build with scripts/build-sdk.sh, run the pure Java validation checks and the isolated-emulator instrumentation tests appropriate to the change. Do not claim live-provider or DroidCommand integration unless actually tested. Any DCA integration change belongs in the droidcommand-AI repo (Victorious93/droidcommand-AI), must follow that repository's instructions, read its security documentation and keep the four excluded fleet apps excluded.
