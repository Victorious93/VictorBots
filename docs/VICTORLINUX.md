# VictorLinux PC profile preset (optional)

Labels used below: **[Certain]** verified in this repository's code or tests, **[Likely]** follows from the code but was not exercised end to end, **[Guessing]** an assumption about something outside this repository.

## What it is

**More → Providers → "＋ Add VictorLinux PC (optional preset)"** creates an ordinary PC provider profile with a few extra rules and shortcuts. It is a convenience form, not a new kind of backend.

* **[Certain]** It is opt-in. Nothing happens until you tap that button. Existing profiles, the default profile, policy, bots, chats, jobs and the database schema are unchanged. No migration was added; the preset is stored as extra keys inside the profile's existing `caps` JSON (`preset`, `runner_url`, `runner_pin`).
* **[Certain]** You enter the PC **host** and an optional **port** (default `8080`). The base URL becomes `https://<host>:<port>/v1` and chat goes to `https://<host>:<port>/v1/chat/completions`. The model is prefilled with `victor-local` and is editable.
* **[Certain]** The form refuses to save, with an inline message, when: the host is empty, contains a scheme, path, port, credentials or spaces (an `http://` host gets an explicit "HTTPS only" message); the port is not 1–65535; the model is empty; the **access token is missing**; the **certificate SHA-256 pin is missing or malformed**.
* **[Certain]** All requests use the same code as every other profile: HTTPS only (`Hosts.base`), redirects refused, per-connection certificate pinning (`Tls.pinnedFactory`; only the exact certificate you pinned is trusted, only for this profile; nothing is trusted app-wide), bounded responses, and the Gateway policy for model text.
* **[Certain]** Chat still goes through `Gateway`. The preset adds no second path for model requests.

## Standalone guarantee (both directions)

* **[Certain] VictorBots without VictorLinux:** with no preset profile, `Profiles.runnerProfile()` returns exactly what `byKind(PC)` returned before, the runner URL is still `<origin>/runner/v1`, keys for profiles without a pin are bound exactly as before, and the only visible differences are the extra button and a PC profile's key line, which now also says when a pinned token must be re-entered. Covered by `tests/jvm/VictorLinuxTest.java` ("default install …" checks) and the unchanged existing suites.
* **[Certain] VictorLinux without VictorBots' runner:** a preset profile needs no runner. Leave **Runner URL** empty and chat, model use and diagnostics work. Jobs, routines and connectors that need a runner show **"Runner not configured for this profile"** and make no request.
* **[Certain]** VictorLinux needs no change to VictorBots to be used, and VictorBots makes no change to VictorLinux. Nothing here touches hub pairing or the DroidCommand signer pin.
* **[Guessing]** The `/v1` OpenAI-compatible path, the default port `8080`, the model name `victor-local` and `GET /victor/capabilities` are taken from the task description, not from VictorLinux sources. No VictorLinux gateway was available to test against.

## Getting the certificate fingerprint

The pin is the SHA-256 of the server's **leaf certificate** (DER). Colons and case are ignored when you paste it.

* **[Certain]** From the certificate file on the PC: `openssl x509 -in /path/to/cert.pem -noout -fingerprint -sha256`
* **[Certain]** From the running server (check you are talking to your own PC first, for example over your VPN or LAN): `openssl s_client -connect <host>:<port> </dev/null 2>/dev/null | openssl x509 -noout -fingerprint -sha256`
* **[Certain]** An expired certificate is rejected even when the pin matches (`checkValidity`). If you renew the certificate you must update the pin, and VictorBots then asks for the token again (see below).

## Capability discovery

The **Detect capabilities** button in the form sends `GET https://<host>:<port>/victor/capabilities` with the typed (or stored, still readable) token, over the same pinned TLS.

* **[Certain]** No redirects (a 3xx is a failure and the target is never requested), connect timeout 8 s, read timeout 10 s, response body capped at 64 KB, no chat text sent, nothing executed.
* **[Certain]** The reply is parsed defensively: unknown fields, wrong types and missing keys are ignored. Tool-call support is recognised from a boolean-ish `tool_calls` / `tools` / `tool_calling` / `function_calling` / `supports_tools` flag at the top level or inside `capabilities` / `features` / `supports`, or a `capabilities` list containing one of those names. A bare `tools` list is **not** treated as support.
* **[Certain]** If it reports tool-call support, the existing "Model supports tool calls" switch is pre-set and a line shows what was detected. If it reports no support or says nothing, the switch is left as you had it. On any failure (TLS, auth, redirect, timeout, bad JSON) you get a non-fatal note and set the switch by hand.
* **[Certain]** After you save, a successful detection is stored as the profile's *advertised* capability (shown as "advertised", never as "tested"). The switch only records what you want; **VictorBots never executes tool calls itself**, and discovery alone starts nothing.
* **[Guessing]** The real JSON shape of `/victor/capabilities` is unknown to this repository. The parser accepts several plausible shapes, so a real reply may need a small parser extension.
* This request is not routed through `Gateway` because it carries no model text. **[Certain]** It cannot be sent without a token and a pin.

## Runner URL (separate from the inference host)

Ordinary PC profiles keep the old layout: the runner is at `/runner/v1` on the inference host and shares its token and pin. For a VictorLinux profile that assumption is not made.

* **[Certain]** Empty **Runner URL**: there is no runner. Creating or running a job, a PC routine or a connector shows "Runner not configured for this profile" and does not call anything.
* **[Certain]** Runner URL on the **same origin** as the PC: shares the profile's token and pin.
* **[Certain]** Runner URL on a **different origin**: it needs its **own certificate pin and its own token** (stored in a separate encrypted slot). The PC's token and pin are never sent to it; a missing runner token is refused before any request.
* **[Certain]** Jobs, routines and connectors use the first PC profile that has a runner, so adding a runner-less VictorLinux profile does not take over an existing runner profile.
* The runner URL is used exactly as typed (for example `https://pc:8443/runner/v1`); no path is guessed.

## Token binding includes the certificate pin

* **[Certain]** For PC profiles, the Keystore AES-GCM secret is bound (GCM associated data and stored label) to `profileId | origin | pin`. Profiles without a pin, and all cloud profiles, are bound to the origin alone, exactly as before.
* **[Certain]** Changing the pin (or address) makes the old token unreadable, `Profiles.save` deletes it, and the form refuses to save without re-entering the token. A changed pin can never silently reuse the old token.
* **[Certain] Migration:** a 0.2.0 PC profile that already had a pin has its token stored under the old, pin-less binding. After this update that token is treated as not usable: the profile card, diagnostics and the Gateway say "enter it again", nothing is sent, nothing crashes, and no key is decrypted or re-encrypted automatically. Re-entering the token in Edit fixes it. PC profiles without a pin need no action.
* **[Likely]** The same applies on a real device; the Keystore code path (`SecretStore`) was compiled and extended with an instrumentation check but **not run on a device or emulator** for this change.

## What is NOT supported yet: pointing the PC runner at VictorLinux

**[Certain]** Do not configure the PC runner's `inference_upstream` to point at a VictorLinux gateway. As of this change:

* `victorbots_runner.py` takes inference upstream only from the `inference_upstream` key in its JSON config. It has **no** `--inference-upstream` command-line flag (its flags are `--config` and `--hash-token`). The flag of that name belongs to `pc-runner/install-ubuntu.sh`, which just writes the config key.
* The pass-through (`Handler.proxy`) sends **no `Authorization` header** upstream (by design: "the phone's token is NOT forwarded"). A token-protected VictorLinux would answer 401/403.
* It does **no certificate pinning** for an `https` upstream. **[Likely]** Python's default HTTPS context verifies against the system trust store, so a self-signed VictorLinux certificate would fail verification.
* By default the upstream must be loopback unless `allow_remote_upstream` is set.

This is a planned follow-up (runner-side token and pin settings for the upstream). Until then, use the preset to talk to VictorLinux **directly from the phone**, and keep the runner (if any) for jobs on its own address.

## What was and was not verified

See `VERIFICATION.md`, section "VictorLinux preset". In short: pure-Java tests against real TLS servers and the real Python runner pass; the APK builds; the UI form was compiled but **not clicked through on a device**, and **no live VictorLinux gateway was used**.
