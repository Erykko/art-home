#!/bin/bash
# Builds Art Home without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
set -euo pipefail

cd "$(dirname "$0")"
SDK=${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}
BT=$SDK/build-tools/36.1.0
JAR=$SDK/platforms/android-34/android.jar
OUT=build

rm -rf "$OUT"
mkdir -p "$OUT/res" "$OUT/gen" "$OUT/classes" "$OUT/dex"

"$BT/aapt2" compile --dir res -o "$OUT/res/compiled.zip"
"$BT/aapt2" link -o "$OUT/unsigned.apk" -I "$JAR" \
    --manifest AndroidManifest.xml --java "$OUT/gen" -A assets \
    "$OUT/res/compiled.zip"

javac -source 11 -target 11 -Xlint:-options -encoding UTF-8 \
    -classpath "$JAR" -d "$OUT/classes" \
    $(find src "$OUT/gen" -name '*.java')

"$BT/d8" --min-api 24 --lib "$JAR" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

cp "$OUT/unsigned.apk" "$OUT/with-dex.apk"
(cd "$OUT/dex" && zip -q -j ../with-dex.apk classes.dex)

"$BT/zipalign" -f -p 4 "$OUT/with-dex.apk" "$OUT/aligned.apk"
"$BT/apksigner" sign --ks "$HOME/.android/debug.keystore" \
    --ks-pass pass:android --key-pass pass:android \
    --out "$OUT/ArtHome.apk" "$OUT/aligned.apk"

echo "Built $OUT/ArtHome.apk"
