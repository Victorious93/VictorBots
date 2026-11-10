#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_HOME:?Set ANDROID_HOME}"
bt="$ANDROID_HOME/build-tools/35.0.0"
platform="$ANDROID_HOME/platforms/android-35/android.jar"
# Remove only generated output before rebuilding.
rm -rf -- build/instrumentation
mkdir -p build/instrumentation/{classes,dex}
cat > build/instrumentation/AndroidManifest.xml <<'XML'
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.victorious.bots.test"><uses-sdk android:minSdkVersion="28" android:targetSdkVersion="35"/><application android:label="VictorBots Tests"/><instrumentation android:name="com.victorious.bots.test.SmokeInstrumentation" android:targetPackage="com.victorious.bots"/></manifest>
XML
"$bt/aapt2" link -o build/instrumentation/unsigned.apk --manifest build/instrumentation/AndroidManifest.xml -I "$platform"
javac -encoding UTF-8 -source 8 -target 8 -cp "$platform:app/build/sdk/classes.jar" -d build/instrumentation/classes tests/android/com/victorious/bots/test/SmokeInstrumentation.java
jar cf build/instrumentation/classes.jar -C build/instrumentation/classes .
"$bt/d8" --lib "$platform" --classpath app/build/sdk/classes.jar --min-api 28 --output build/instrumentation/dex build/instrumentation/classes.jar
python3 - <<'PY'
from pathlib import Path
from zipfile import ZipFile,ZIP_DEFLATED
with ZipFile('build/instrumentation/unsigned.apk','a',ZIP_DEFLATED) as z:
 for p in Path('build/instrumentation/dex').glob('*.dex'):z.write(p,p.name)
PY
"$bt/zipalign" -f 4 build/instrumentation/unsigned.apk build/instrumentation/aligned.apk
"$bt/apksigner" sign --ks signing/victorbots-debug.jks --ks-key-alias androiddebugkey --ks-pass pass:android --out build/instrumentation/tests.apk build/instrumentation/aligned.apk
