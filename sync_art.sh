#!/bin/bash
# Pulls captured Art mode screenshots from the TV, drops duplicates, converts to JPEG
# and pushes them into Art Home's art folder.
set -euo pipefail

ADB="${ADB_BIN:-$HOME/Library/Android/sdk/platform-tools/adb} -s ${TV:?Set TV to the TV address, e.g. TV=192.168.1.50:5555}"
SRC=/sdcard/ArtCapture
DEST=/sdcard/Android/data/com.eric.artlauncher/files/art
WORK=$(mktemp -d /tmp/artsync.XXXX)

$ADB pull "$SRC/titles.txt" "$WORK/titles.txt" >/dev/null

# Keep the first capture of each title; untitled captures are kept as-is.
: > "$WORK/keep.txt"
awk -F'|' 'NF>=2 { if ($2 ~ /^untitled_/ || !seen[$2]++) print }' "$WORK/titles.txt" > "$WORK/keep.txt"

mkdir -p "$WORK/png" "$WORK/jpg"
while IFS='|' read -r n title _rest; do
    $ADB pull "$SRC/$n.png" "$WORK/png/$n.png" >/dev/null
    sips -s format jpeg -s formatOptions 85 "$WORK/png/$n.png" --out "$WORK/jpg/$n.jpg" >/dev/null
done < "$WORK/keep.txt"

cp "$WORK/keep.txt" "$WORK/jpg/titles.txt"
$ADB shell "mkdir -p $DEST && rm -f $DEST/*"
$ADB push "$WORK/jpg/." "$DEST/" >/dev/null

echo "Pushed $(ls "$WORK/jpg" | grep -c '\.jpg$') artworks ($(du -sh "$WORK/jpg" | cut -f1))"
rm -rf "$WORK"
