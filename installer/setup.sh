#!/usr/bin/env bash
# Explicit opt-in installer for a *signed, prebuilt* Cleaner TV Liquid Glass release.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
: "${TV_HOST:?Set TV_HOST to the IP of your Android TV after enabling ADB debugging.}"
test -s CleanYourTV-Scenic.apk || { echo 'Extract the full Setup ZIP first.' >&2; exit 1; }
command -v python3 >/dev/null || { echo 'Python 3 is required.' >&2; exit 1; }

if [ ! -x tools/venv/bin/python ] && [ ! -x tools/venv/Scripts/python.exe ]; then
  python3 -m venv tools/venv
fi
PY=tools/venv/bin/python
[ -x "$PY" ] || PY=tools/venv/Scripts/python.exe
"$PY" -m pip -q install -r tools/requirements.txt

echo 'Read-only discovery: TV model, Android version, firmware, Home and disabled packages.'
"$PY" -I tools/tv.py --probe
fingerprint="$("$PY" -I tools/tv.py 'getprop ro.build.fingerprint' | tr -d '\r' | tail -n 1)"
test -n "$fingerprint" || { echo 'Cannot establish firmware fingerprint; nothing changed.' >&2; exit 1; }

home="$("$PY" -I tools/tv.py 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME' | tr -d '\r' | tail -n 1)"
home_pkg="${home%%/*}"
case "$home_pkg" in
  android|com.gathin.tvhome|com.google.android.tvlauncher|com.google.android.apps.tv.launcherx|com.google.android.leanbacklauncher|com.mitv.tvhome) ;;
  *) echo "Unrecognised default Home ($home): stock launcher stays enabled; check manually before continuing." ;;
esac
mkdir -p "$ROOT/recovery"
printf '%s\n' "$home" > "$ROOT/recovery/home-before-install.txt"
"$PY" -I tools/tv.py 'pm list packages -d' > "$ROOT/recovery/packages-disabled-before-install.txt"
echo "Original Home: $home; recovery snapshot in recovery/"

echo 'Installing the signed APK and selecting Home, WITHOUT disabling the stock launcher.'
STOCK_LAUNCHER=none PREBUILT_APK="$ROOT/CleanYourTV-Scenic.apk" "$ROOT/launcher/build-and-install.sh"

# Model-based optimization is separate from Home installation and never runs by default.
matched=()
for profile in examples/*/debloat.txt; do
  test -f "$profile" || continue
  reference="$(sed -n 's/^# Fingerprint: //p' "$profile" | head -n 1 | tr -d '\r')"
  if [ -n "$reference" ] && [ "$reference" = "$fingerprint" ]; then matched+=("$profile"); fi
done
echo
if [ "${#matched[@]}" -eq 1 ]; then
  profile="${matched[0]}"
  echo "Exact firmware profile available: $profile"
  echo 'Read its comments. Chromecast, Ambilight, HDMI, live TV and original Home must be preserved.'
  answer=''
  if [ -t 0 ]; then read -r -p 'Type OPTIMIZAR to enable its optional debloat (Enter skips): ' answer || true; fi
  if [ "$answer" = OPTIMIZAR ]; then
    "$PY" -I tools/recovery.py --save "$profile"
    "$ROOT/tools/disable.sh" "$profile"
    echo "Rollback: TV_HOST=$TV_HOST tools/restore.sh $profile"
  else
    echo 'No debloat applied.'
  fi
else
  echo 'No single matching fingerprint profile; debloat not offered. The launcher can still be used.'
fi
echo 'When finished testing, disable ADB debugging in TV settings.'
