# VictorBots — Victorious Framework

An independent Android custom-bot companion for DroidCommand AI. Version 0.1.0 is a development build for Android 9 (API 28) and later. It needs no root access.

## What is built

- Editable custom bots: name, description, instructions, symbol, and optional image avatar.
- Chief of Staff, Android Engineer, QA Auditor, and Knowledge Keeper starter bots.
- Separate persistent conversations, new chats, chat history, and explicit deletion.
- Recent-context budget of 40,000 content characters; current requests and instructions are never silently truncated. Older turns can be omitted. This is a character budget, not a token estimate.
- DroidCommand's existing `COMPANION_ASSIST` intent protocol, with explicit component targeting, signing-certificate verification, per-request consent, and structured result parsing.
- Optional HTTPS OpenAI-compatible chat-completions provider, with Android Keystore-encrypted API key storage. Provider redirects are refused. Changing endpoints cannot silently reuse an existing stored key.
- Task drafts and manual task status. Long-press a reply to copy it, save it as a task, or prepare context in another bot's new conversation. That context is not sent until you choose Send.
- Workspace JSON export through Android's document picker. Includes profiles, instructions, messages and task drafts. Excludes API keys and avatar image bytes.

## Install and use

1. Install `VictorBots-0.1.0-debug.apk` on Android 9 or later. Allow installation from the app opening the APK if Android asks.
2. Open VictorBots → Connect.
3. Choose one of the two real chat paths below.
4. Open a starter bot, or create a bot. Edit a bot and select its avatar from the conversation's menu.

### DroidCommand AI

Install DroidCommand on the **same Android device**. This version uses local Android IPC, not Wi-Fi pairing or cross-device transport.

DroidCommand-side registration and hub pairing live in the droidcommand-AI repo
([Victorious93/droidcommand-AI](https://github.com/Victorious93/droidcommand-AI)) and are owned there.
Do not apply or duplicate that pairing code from this repository.

DroidCommand links VictorBots to the installed signing certificate through owner-confirmed
password pairing. Install a DroidCommand build that already includes that registration
alongside VictorBots and proceed to pairing.

Use DroidCommand's documented JDK, SDK, NDK and CMake prerequisites. Install the
DroidCommand build alongside VictorBots 0.1.0. Configure a working chat model in DroidCommand.
In **Settings → Password pairing**, save a password and turn on **Allow password pairing**.
Send a message from VictorBots using DroidCommand. Verify the caller, type the password
in DroidCommand and tap **Allow**. Later requests still ask for approval.

The default pinned DroidCommand certificate comes from its inspected `NodeSignature.kt` and `docs/SIGNING.md`:

```
88:00:51:A6:D9:5D:38:DD:8D:81:E1:93:1E:17:05:48:03:87:D5:93:C3:09:43:53:99:68:E6:EF:11:A0:C8:4D
```

For an independently verified release-signed DroidCommand APK, enter its verified SHA-256 in Connect. Do not paste a fingerprint from an untrusted APK simply to silence a mismatch.

### Direct HTTPS provider

Enable “Use a direct HTTPS provider,” enter a complete HTTPS endpoint ending in `/chat/completions`, a model identifier supported by that provider, and its API key if required. Save, then send a message. This path sends your chosen bot instructions and recent chat directly to that host; the confirmation names the host.

There are no bundled credentials, assumed model names, simulated AI replies, or invented subscription-login APIs. An ordinary chatbot subscription does not automatically supply API access. Any provider-supported authentication inside DroidCommand remains DroidCommand's responsibility.

## Device tasks and truthful status

DroidCommand's verified inbound companion-assist activity only returns text. It explicitly does **not** execute tools or `remote.command`.

Tasks therefore use **Copy & open DroidCommand**: review a task, copy it, open the verified DroidCommand package, and paste it into its normal workflow. The task is not submitted automatically. “Prepared for handoff,” “In progress (manual),” and “Done (manual)” are user-managed states, not execution receipts. Bot-to-bot handoff similarly prepares a draft; it is not autonomous delegation.

Automatic device execution, remote pairing, background routines, voice, cross-device sync, and subscription sign-in within VictorBots are not implemented in this release.

## Build from source

The APK was compiled using the dependency-free SDK path below. The app uses native Android Java APIs, SQLite, Android Keystore, and HTTPS; no third-party runtime libraries or native ABI-specific binaries are required.

Prerequisites: JDK 17+, Python 3, Android SDK `platforms;android-35` and `build-tools;35.0.0`. SDK Platform Tools are needed to install/test with ADB. On Linux/macOS, set the paths for your machine:

```bash
export JAVA_HOME=/absolute/path/to/jdk-17
export ANDROID_HOME=/absolute/path/to/android-sdk
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
bash scripts/build-sdk.sh
# app/build/sdk/VictorBots-0.1.0-debug.apk
```

A conventional Gradle 8.9 / Android Gradle Plugin 8.7.3 project and wrapper are also included:

```bash
./gradlew :app:assembleDebug
```

The Gradle APK path could not be verified in the build environment because the AGP plugin repository could not resolve. The SDK build above completed successfully. Toolchain compatibility reference: https://developer.android.com/build/releases/agp-8-7-0-release-notes

## Tests

```bash
mkdir -p build/tests
javac -d build/tests app/src/main/java/com/victorious/bots/Rules.java tests/RulesTest.java
java -cp build/tests RulesTest
bash scripts/build-test-sdk.sh
adb install -r app/build/sdk/VictorBots-0.1.0-debug.apk
adb install -r build/instrumentation/tests.apk
adb shell am instrument -w com.victorious.bots.test/com.victorious.bots.test.SmokeInstrumentation
```

Instrumentation is intended for an **isolated emulator** without DroidCommand installed; it creates and removes a test bot and exercises the provider secret slot. Do not run it against a populated personal installation. See `VERIFICATION.md` for actual results and limitations.

## Signing, privacy and source ownership

The bundled `signing/victorbots-debug.jks` is a public **development-only** key, alias `androiddebugkey`, password `android`. It preserves development update compatibility. Use a private release signing key for production builds. Revoke the old DroidCommand link and pair again after changing the signer. Do not reuse DroidCommand's or another app's signing key.

VictorBots debug signer:

```
23:CE:FC:35:FA:9B:8C:E1:F5:D3:CC:E7:B1:89:AA:34:0F:F6:50:51:83:4C:C8:99:6E:AD:A0:8D:6A:0F:5B:82
```

Chats are private app data, not an independently encrypted database. Android backup is disabled; uninstalling deletes local data. Exports are plaintext documents chosen explicitly by you. Copy operations place text on the Android clipboard. Network requests have finite timeouts and are never automatically retried, because the remote side may already have processed a request.

App source created for Victorious Framework. No Grokbot source, branding, private API, or assets were copied. No public redistribution license has been selected for the app source. The Gradle wrapper retains its upstream notices.
