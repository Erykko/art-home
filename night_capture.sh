#!/bin/bash
# Overnight NXTFRAME art capture: opens Art mode's ALL Pictures on the TV, skips pieces
# already captured, captures the rest, loads them into Art Home and restores standby.
# Scheduled once by ~/Library/LaunchAgents/com.eric.artlauncher.nightcapture.plist,
# which this script removes when it finishes.
set -uo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
TV=${TV:?Set TV to the TV address, e.g. TV=192.168.1.50:5555}
ADB=${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}
LOG="$DIR/night_capture.log"
PLIST="$HOME/Library/LaunchAgents/com.eric.artlauncher.nightcapture.plist"
FIRST_TITLE="The Swan, No. 01, Group IX/SUW"

tv() { "$ADB" -s "$TV" "$@"; }
key() { for k in "$@"; do tv shell input keyevent "KEYCODE_$k"; sleep 0.8; done; }
log() { echo "$(date '+%F %T') $*" >> "$LOG"; }
# The TV's UI dump sometimes fails silently, so delete the old file first and retry.
focused() {
    for _ in 1 2 3 4 5; do
        tv shell rm -f /sdcard/night_ui.xml
        tv shell uiautomator dump /sdcard/night_ui.xml >/dev/null 2>&1
        out=$(tv shell cat /sdcard/night_ui.xml 2>/dev/null)
        if [ -n "$out" ] && echo "$out" | grep -q '<hierarchy'; then
            echo "$out" | grep -oE '<node[^>]*focused="true"[^>]*>' \
                | grep -oE '(text|content-desc)="[^"]*"' | tr '\n' ' '
            return
        fi
        sleep 2
    done
}
finish() {
    log "finished: $1"
    # Always leave the TV on Art Home (and back in standby if it was asleep).
    if [ "${opened_exhibit:-0}" = 1 ]; then
        tv shell input keyevent KEYCODE_HOME
        [ "${was_asleep:-0}" = 1 ] && { sleep 2; tv shell input keyevent KEYCODE_SLEEP; }
    fi
    tv shell rm -f /sdcard/night_ui.xml /sdcard/cap_ui.xml 2>/dev/null
    launchctl unload "$PLIST" 2>/dev/null
    rm -f "$PLIST"
    exit 0
}

# Only one run at a time (the LaunchAgent and a Claude schedule may both fire).
LOCK=/tmp/artlauncher_night.lock
mkdir "$LOCK" 2>/dev/null || { log "another run is in progress, exiting"; exit 0; }
trap 'rmdir "$LOCK" 2>/dev/null' EXIT

log "starting"
"$ADB" connect "$TV" >/dev/null 2>&1
tv get-state >/dev/null 2>&1 || finish "TV not reachable"

# Don't interrupt anyone: skip if any media session is playing.
if tv shell dumpsys media_session | grep -qE "state=PlaybackState \{state=(PLAYING|3,)"; then
    finish "skipped, something is playing"
fi

was_asleep=0
if tv shell dumpsys power | grep -q "mWakefulness=Asleep\|mWakefulness=Dozing"; then
    was_asleep=1
    key WAKEUP
    sleep 8
fi

# Open Exhibit fresh on the Gallery tab, then ALL Pictures.
tv shell am force-stop com.tcl.exhibit
tv shell monkey -p com.tcl.exhibit -c android.intent.category.LEANBACK_LAUNCHER 1 >/dev/null 2>&1
opened_exhibit=1
sleep 8
key DPAD_UP
for _ in 1 2 3 4 5 6 7 8; do
    focused | grep -q "ALL Pictures" && break
    key DPAD_RIGHT
done
log "focus before opening: $(focused)"
focused | grep -q "ALL Pictures" || finish "could not find ALL Pictures"
key DPAD_CENTER
sleep 5
key DPAD_CENTER          # open the first piece in preview
sleep 6

# Skip the pieces already captured.
start=$(tv shell "grep -c '^[0-9][0-9][0-9]|' /sdcard/ArtCapture/titles.txt 2>/dev/null" | tr -d '\r')
start=${start:-0}
log "skipping $start already-captured pieces"
for _ in $(seq 1 "$start"); do tv shell input keyevent KEYCODE_DPAD_RIGHT; sleep 1.5; done
sleep 4

tv push "$DIR/cap.sh" /sdcard/cap.sh >/dev/null
tv shell sh /sdcard/cap.sh "$start" "'$FIRST_TITLE'" >> "$LOG" 2>&1
log "capture done: $(tv shell "grep -c '^[0-9][0-9][0-9]|' /sdcard/ArtCapture/titles.txt" | tr -d '\r') pieces total"

"$DIR/sync_art.sh" >> "$LOG" 2>&1

key BACK BACK
finish "ok"
