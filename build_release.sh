#!/bin/bash
# Public release build: a copy with a neutral package name (app.arthome) and no personal
# defaults, signed with a dedicated release key that is NOT in this repository.
#   RELEASE_KEYSTORE=~/.arthome/release.jks RELEASE_KEY_PASS=... ./build_release.sh
set -euo pipefail

cd "$(dirname "$0")"
KS=${RELEASE_KEYSTORE:?Set RELEASE_KEYSTORE to the release keystore}
PASS=${RELEASE_KEY_PASS:?Set RELEASE_KEY_PASS}
SDK=${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}
BT=$SDK/build-tools/36.1.0
JAR=$SDK/platforms/android-34/android.jar
OLD=com.eric.artlauncher
NEW=app.arthome
WORK=$(mktemp -d /tmp/arthome-release.XXXX)
OUT=release
VERSION=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' AndroidManifest.xml)

# 1. Neutral copy of the sources.
cp -R AndroidManifest.xml res assets "$WORK/"
mkdir -p "$WORK/src/${NEW//.//}"
for f in src/${OLD//.//}/*.java; do
    sed -e "s/$OLD/$NEW/g" -e 's/e\.g\. Nairobi/e.g. London/g' "$f" > "$WORK/src/${NEW//.//}/$(basename "$f")"
done
sed -i '' "s/$OLD/$NEW/g" "$WORK/AndroidManifest.xml"
grep -rqiE "com[./]eric|\beric\b|nairobi|mutema" "$WORK/src" "$WORK/AndroidManifest.xml" "$WORK/res" && { echo "personal string left in sources"; exit 1; }

# 2. Build.
B="$WORK/build"
mkdir -p "$B/gen" "$B/classes" "$B/dex"
"$BT/aapt2" compile --dir "$WORK/res" -o "$B/res.zip"
"$BT/aapt2" link -o "$B/unsigned.apk" -I "$JAR" --manifest "$WORK/AndroidManifest.xml" \
    --java "$B/gen" -A "$WORK/assets" "$B/res.zip"
javac -source 11 -target 11 -Xlint:-options -encoding UTF-8 -classpath "$JAR" -d "$B/classes" \
    $(find "$WORK/src" "$B/gen" -name '*.java')
"$BT/d8" --release --min-api 24 --lib "$JAR" --output "$B/dex" $(find "$B/classes" -name '*.class')
cp "$B/unsigned.apk" "$B/with-dex.apk"
(cd "$B/dex" && zip -q -j ../with-dex.apk classes.dex)
"$BT/zipalign" -f -p 4 "$B/with-dex.apk" "$B/aligned.apk"
mkdir -p "$OUT"
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$PASS" --key-pass "pass:$PASS" \
    --out "$OUT/ArtHome-$VERSION.apk" "$B/aligned.apk"

# 3. Verify nothing personal is inside.
unzip -p "$OUT/ArtHome-$VERSION.apk" classes.dex | strings | grep -qiE "com[./]eric|\beric\b|nairobi|mutema" \
    && { echo "personal string found in APK"; exit 1; }
rm -rf "$WORK"
echo "Built $OUT/ArtHome-$VERSION.apk"
