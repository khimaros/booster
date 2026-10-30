#!/bin/sh
# measures drawer open/close frame times on a device: jank.sh <adb> <port> <serial> [cycles]
# prints the gfxinfo summary for the launcher after the swipes.
set -eu
ADB="$1"
PORT="$2"
SERIAL="$3"
CYCLES="${4:-10}"
PKG=com.khimaros.booster
a() { "$ADB" -P "$PORT" -s "$SERIAL" "$@"; }

# an override size, when set, is listed last
size=$(a shell wm size | sed -n 's/.*: \([0-9]*\)x\([0-9]*\).*/\1 \2/p' | sed '$!d')
w=${size% *}
h=${size#* }
x=$((w / 2))
a shell am start -W -a android.intent.action.MAIN -c android.intent.category.HOME > /dev/null
sleep 1
a shell dumpsys gfxinfo "$PKG" reset > /dev/null
i=0
while [ "$i" -lt "$CYCLES" ]; do
    a shell input swipe "$x" $((h * 3 / 4)) "$x" $((h / 4)) 150
    sleep 0.8
    a shell input swipe "$x" $((h / 4)) "$x" $((h * 3 / 4)) 150
    sleep 0.8
    i=$((i + 1))
done
a shell dumpsys gfxinfo "$PKG" > /tmp/booster-gfxinfo.txt
grep -E "Total frames|Janky frames|percentile|Number (Missed|Slow)" /tmp/booster-gfxinfo.txt
