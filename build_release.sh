#!/bin/bash
# Public release builds from a copy of the sources with a neutral package name (app.arthome)
# and no personal defaults, signed with the release key (never stored in this repository):
#   release/ArtHome-<version>.apk   sideload build (includes phone self-update)
#   release/ArtHome-<version>.aab   Google Play bundle (self-update removed, as Play requires)
#
#   RELEASE_KEYSTORE=path/to/release.jks RELEASE_KEY_PASS=... ./build_release.sh
set -euo pipefail

cd "$(dirname "$0")"
KS=${RELEASE_KEYSTORE:?Set RELEASE_KEYSTORE to the release keystore}
PASS=${RELEASE_KEY_PASS:?Set RELEASE_KEY_PASS}
ALIAS=${RELEASE_KEY_ALIAS:-arthome}
SDK=${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}
BT=$SDK/build-tools/${BUILD_TOOLS:-36.1.0}
JAR=$SDK/platforms/${PLATFORM:-android-36}/android.jar
BUNDLETOOL=${BUNDLETOOL:-$HOME/.arthome/tools/bundletool.jar}
OLD=com.eric.artlauncher
NEW=app.arthome
OUT=release
VERSION=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' AndroidManifest.xml)
PERSONAL='com[./]eric|\beric\b|nairobi|mutema'
mkdir -p "$OUT"

sedi() { if sed --version >/dev/null 2>&1; then sed -i "$@"; else sed -i '' "$@"; fi; }

# Neutral copy of the sources; $2 = true for the Play flavour.
prepare() {
    local dir=$1 play=$2
    cp -R AndroidManifest.xml res assets "$dir/"
    mkdir -p "$dir/src/${NEW//.//}"
    for f in src/${OLD//.//}/*.java; do
        sed -e "s/$OLD/$NEW/g" -e 's/e\.g\. Nairobi/e.g. London/g' "$f" > "$dir/src/${NEW//.//}/$(basename "$f")"
    done
    sedi "s/$OLD/$NEW/g" "$dir/AndroidManifest.xml"
    if [ "$play" = true ]; then
        sedi 's/PLAY_STORE = false/PLAY_STORE = true/' "$dir/src/${NEW//.//}/Flavor.java"
        sedi '/REQUEST_INSTALL_PACKAGES/d; /Self-update from the phone upload page/d' "$dir/AndroidManifest.xml"
    fi
    if grep -rqiE "$PERSONAL" "$dir/src" "$dir/AndroidManifest.xml" "$dir/res"; then
        echo "personal string left in sources"; exit 1
    fi
}

compile() {
    local dir=$1 b=$1/build
    mkdir -p "$b/gen" "$b/classes" "$b/dex"
    "$BT/aapt2" compile --dir "$dir/res" -o "$b/res.zip"
    javac_and_dex() {
        javac -source 11 -target 11 -Xlint:-options -encoding UTF-8 -classpath "$JAR" -d "$b/classes" \
            $(find "$dir/src" "$b/gen" -name '*.java')
        "$BT/d8" --release --min-api 24 --lib "$JAR" --output "$b/dex" $(find "$b/classes" -name '*.class')
    }
    "$BT/aapt2" link "${@:2}" -I "$JAR" --manifest "$dir/AndroidManifest.xml" \
        --java "$b/gen" -A "$dir/assets" "$b/res.zip"
    javac_and_dex
}

check_clean() {
    if unzip -p "$1" '*.dex' | strings | grep -qiE "$PERSONAL"; then
        echo "personal string found in $1"; exit 1
    fi
}

# --- Sideload APK -------------------------------------------------------------------------
W=$(mktemp -d /tmp/arthome-apk.XXXX)
prepare "$W" false
compile "$W" -o "$W/build/unsigned.apk"
cp "$W/build/unsigned.apk" "$W/build/with-dex.apk"
(cd "$W/build/dex" && zip -q -j ../with-dex.apk classes.dex)
"$BT/zipalign" -f -p 4 "$W/build/with-dex.apk" "$W/build/aligned.apk"
"$BT/apksigner" sign --ks "$KS" --ks-key-alias "$ALIAS" --ks-pass "pass:$PASS" --key-pass "pass:$PASS" \
    --out "$OUT/ArtHome-$VERSION.apk" "$W/build/aligned.apk"
check_clean "$OUT/ArtHome-$VERSION.apk"
rm -rf "$W"
echo "Built $OUT/ArtHome-$VERSION.apk"

# --- Google Play bundle -------------------------------------------------------------------
W=$(mktemp -d /tmp/arthome-aab.XXXX)
prepare "$W" true
compile "$W" --proto-format -o "$W/build/proto.zip"
# bundletool module layout: manifest/, dex/, res/, assets/, resources.pb
M="$W/build/module"
mkdir -p "$M/manifest" "$M/dex"
(cd "$M" && unzip -q ../proto.zip)
mv "$M/AndroidManifest.xml" "$M/manifest/"
cp "$W/build/dex/classes.dex" "$M/dex/"
(cd "$M" && zip -q -r ../base.zip .)
rm -f "$OUT/ArtHome-$VERSION.aab"
java -jar "$BUNDLETOOL" build-bundle --modules="$W/build/base.zip" --output="$OUT/ArtHome-$VERSION.aab"
jarsigner -keystore "$KS" -storepass "$PASS" -keypass "$PASS" -sigalg SHA256withRSA -digestalg SHA-256 \
    "$OUT/ArtHome-$VERSION.aab" "$ALIAS" >/dev/null
check_clean "$OUT/ArtHome-$VERSION.aab"
rm -rf "$W"
echo "Built $OUT/ArtHome-$VERSION.aab"
