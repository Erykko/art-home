#!/system/bin/sh
# Runs on the TV while Exhibit's preview is open: captures each artwork without overlays,
# then presses RIGHT for the next one. Usage: sh cap.sh <start_index> <stop_title>
# Appends "NNN|title|artist|description" lines to /sdcard/ArtCapture/titles.txt;
# stops when <stop_title> comes round again.
OUT=/sdcard/ArtCapture
START=${1:-0}
STOP_TITLE="$2"
mkdir -p $OUT
touch $OUT/titles.txt
i=$START
while [ $i -lt 400 ]; do
  title=""; artist=""; desc=""
  tries=0
  while [ $tries -lt 30 ]; do
    uiautomator dump /sdcard/cap_ui.xml >/dev/null 2>&1
    x=$(cat /sdcard/cap_ui.xml)
    case "$x" in
      *"Resource is loading"*) ;;
      *"Switch works"*)
        info=$(echo "$x" | sed 's/<node/\n<node/g' | grep -o 'text="[^"]*"' | sed 's/text="//;s/"$//' \
          | grep -v -e '^Press$' -e 'Switch works' -e 'custom operations' -e '^$' | tr -d '|' | head -3)
        title=$(echo "$info" | sed -n 1p)
        artist=$(echo "$info" | sed -n 2p)
        desc=$(echo "$info" | sed -n 3p) ;;
      *) break ;;
    esac
    sleep 1
    tries=$((tries+1))
  done
  [ -z "$title" ] && title="untitled_$i"
  if [ -n "$STOP_TITLE" ] && [ "$title" = "$STOP_TITLE" ]; then echo "wrapped at $i" >> $OUT/titles.txt; break; fi
  # Backup stop: two known titles in a row means we've looped past the start.
  case "$title" in untitled_*) ;; *)
    if awk -F'|' -v t="$title" '$2 == t { found = 1 } END { exit !found }' $OUT/titles.txt; then
      dupes=$((dupes+1)); [ $dupes -ge 2 ] && { echo "looped at $i" >> $OUT/titles.txt; break; }
      input keyevent KEYCODE_DPAD_RIGHT; sleep 2; continue
    fi
    dupes=0 ;;
  esac
  n=$(printf %03d $i)
  screencap -p $OUT/$n.png
  echo "$n|$title|$artist|$desc" >> $OUT/titles.txt
  i=$((i+1))
  input keyevent KEYCODE_DPAD_RIGHT
  sleep 2
done
echo DONE >> $OUT/titles.txt
