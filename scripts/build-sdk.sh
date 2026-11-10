#!/usr/bin/env bash
# Dependency-free Android SDK build. Requires JDK 17+, SDK platform 35 and build-tools 35.0.0.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_HOME:?Set ANDROID_HOME to your Android SDK directory}"
sdk_tools="$ANDROID_HOME/build-tools/35.0.0"
platform_jar="$ANDROID_HOME/platforms/android-35/android.jar"
# Remove only generated output before rebuilding.
rm -rf -- app/build/sdk
mkdir -p app/build/sdk/{classes,dex,generated}
python3 - <<'PY'
from pathlib import Path
import xml.etree.ElementTree as ET
ET.register_namespace('android','http://schemas.android.com/apk/res/android')
p=Path('app/src/main/AndroidManifest.xml')
r=ET.parse(p).getroot();r.set('package','com.victorious.bots')
ET.ElementTree(r).write('app/build/sdk/AndroidManifest.xml',encoding='utf-8',xml_declaration=True)
PY
"$sdk_tools/aapt2" compile --dir app/src/main/res -o app/build/sdk/resources.zip
"$sdk_tools/aapt2" link -o app/build/sdk/unsigned.apk --manifest app/build/sdk/AndroidManifest.xml -I "$platform_jar" --java app/build/sdk/generated --min-sdk-version 28 --target-sdk-version 35 --version-code 1 --version-name 0.1.0 app/build/sdk/resources.zip
find app/src/main/java app/build/sdk/generated -name '*.java' -print > app/build/sdk/sources.txt
javac -encoding UTF-8 -source 8 -target 8 -cp "$platform_jar" -d app/build/sdk/classes @app/build/sdk/sources.txt
jar cf app/build/sdk/classes.jar -C app/build/sdk/classes .
"$sdk_tools/d8" --lib "$platform_jar" --min-api 28 --output app/build/sdk/dex app/build/sdk/classes.jar
python3 - <<'PY'
from pathlib import Path
from zipfile import ZipFile,ZIP_DEFLATED
with ZipFile('app/build/sdk/unsigned.apk','a',ZIP_DEFLATED) as z:
 for p in Path('app/build/sdk/dex').glob('*.dex'):z.write(p,p.name)
PY
"$sdk_tools/zipalign" -f -p 4 app/build/sdk/unsigned.apk app/build/sdk/aligned.apk
"$sdk_tools/apksigner" sign --ks signing/victorbots-debug.jks --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out app/build/sdk/VictorBots-0.1.0-debug.apk app/build/sdk/aligned.apk
"$sdk_tools/apksigner" verify --verbose --print-certs app/build/sdk/VictorBots-0.1.0-debug.apk
