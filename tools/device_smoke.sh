#!/usr/bin/env bash
# Installs the built APK on an Android emulator (CI runs it on Android 8 to 15), walks through
# the app like a person (tools/device_walk.py), then taps around the main screen at random, and
# fails if the app crashes. Screenshots, screen texts and the log go to device-report/.
# Usage: tools/device_smoke.sh <api level>   (test songs are expected in device-songs/)
set -u
API="$1"
PKG=app.malachite.player
APK=$(ls apk/*.apk | head -1)
OUT="device-report/api-$API"
mkdir -p "$OUT"

echo "Android API $API: installing $APK"
# -g grants the permissions the app asks for at run time (music and files up to Android 10, notifications)
adb install -r -g "$APK" || { echo "install failed"; exit 1; }
# Android 11+: "access to all files" is a special permission, given here the way Settings would
if [ "$API" -ge 30 ]; then
  adb shell appops set --uid "$PKG" MANAGE_EXTERNAL_STORAGE allow || true
fi

# test songs (made by CI with tags) where a person keeps music
adb shell mkdir -p /sdcard/Music/TestSongs
for f in device-songs/*; do adb push "$f" /sdcard/Music/TestSongs/ > /dev/null; done
for f in device-songs/*; do
  adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file:///sdcard/Music/TestSongs/$(basename "$f")" > /dev/null 2>&1 || true
done
adb logcat -c || true
# The system log is copied to this machine while it is written: when the emulator stops
# answering, what it wrote last is still here.
adb logcat -v threadtime > "$OUT/logcat-live.txt" 2>/dev/null &
LOGCAT=$!

# -u: every line reaches the build log at once
python3 -u tools/device_walk.py "$API"

# An emulator that stopped answering: show what its log said last and stop at once (instead of
# waiting for it until the job is cancelled).
if ! timeout 30 adb shell echo ok > /dev/null 2>&1; then
  echo "::error::Android API $API: the emulator stopped answering"
  kill "$LOGCAT" 2>/dev/null || true
  echo "  errors and warnings it logged last:"
  grep -E " [EFW] |FATAL|ANR|Watchdog|lowmemorykiller|lmkd" "$OUT/logcat-live.txt" | tail -n 150 | cut -c1-260 | sed 's/^/    /'
  echo "  its very last lines:"
  tail -n 60 "$OUT/logcat-live.txt" | cut -c1-260 | sed 's/^/    /'
  exit 1
fi

# random taps and swipes inside the app only (the same seed every run, so a crash can be repeated)
timeout 30 adb shell am start -n "$PKG/.MainActivity" > /dev/null 2>&1 || true
sleep 3
timeout 300 adb shell monkey -p "$PKG" -s 4242 --throttle 150 --pct-syskeys 0 --pct-appswitch 0 \
  --ignore-security-exceptions -v -v 300 > "$OUT/monkey.txt" 2>&1 || true
grep -E "Events injected" "$OUT/monkey.txt" | sed 's/^/  monkey: /'
timeout 30 adb shell cmd statusbar collapse > /dev/null 2>&1 || true
kill "$LOGCAT" 2>/dev/null || true
timeout 120 adb logcat -d > "$OUT/logcat.txt" || cp "$OUT/logcat-live.txt" "$OUT/logcat.txt"

failed=0
# a crash of our process: "FATAL EXCEPTION" followed by "Process: app.malachite.player"
if grep -A2 "FATAL EXCEPTION" "$OUT/logcat.txt" | grep -q "Process: $PKG"; then
  echo "::error::Android API $API: the app crashed"
  grep -A40 "FATAL EXCEPTION" "$OUT/logcat.txt" | head -120
  failed=1
fi
if grep -q "// CRASH: $PKG" "$OUT/monkey.txt"; then
  echo "::error::Android API $API: the app crashed while tapping"
  grep -A40 "// CRASH: $PKG" "$OUT/monkey.txt" | head -80
  failed=1
fi
# a slow emulator can be "not responding" without a real problem: reported, not failed
if grep -q "// NOT RESPONDING: $PKG\|ANR in $PKG" "$OUT/monkey.txt" "$OUT/logcat.txt"; then
  echo "::warning::Android API $API: the app was not responding for a while (emulator)"
fi
# errors in the system log from the app's processes (not crashes): counted, the first few shown
pids=$( { grep -oE "Start proc [0-9]+:$PKG" "$OUT/logcat.txt" | grep -oE "[0-9]+"; timeout 20 adb shell pidof "$PKG" | tr -d '\r'; } | tr ' ' '\n' | sort -u | tr '\n' '|' | sed 's/|$//')
if [ -n "$pids" ]; then
  grep -E "^[0-9-]+ [0-9:.]+ +($pids) +[0-9]+ E " "$OUT/logcat.txt" > "$OUT/app-errors.txt" || true
  echo "  errors in the system log from the app: $(wc -l < "$OUT/app-errors.txt")"
  head -12 "$OUT/app-errors.txt" | cut -c1-240 | sed 's/^/    /'
fi
[ "$failed" = 0 ] && echo "Android API $API: walked through the app and survived random taps, no crash"
exit $failed
