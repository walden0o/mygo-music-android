#!/usr/bin/env bash
# =============================================================================
# shot.sh — emulator screenshot + UI driver for mygo-music-android
#            (dev.walden.mygomusic, MainActivity, AVD "pin37" headless)
#
# USAGE
#   tools/shot.sh <name> [--no-install] [--wait N] [--keep]
#       Install app/build/outputs/apk/debug/app-debug.apk (unless --no-install),
#       force-stop + relaunch .MainActivity (unless --keep), wait N seconds
#       (default 4), screencap -> screenshots/<name>.png, downscale to width 900
#       when ImageMagick is available. Prints ABSOLUTE path + file size.
#       --keep  = do NOT relaunch; capture whatever state is currently on screen.
#       --wait 0 = skip the settling delay.
#
#   tools/shot.sh capture <name>       alias for <name> --keep --no-install
#       (use this after tap/swipe/back to snapshot the current UI state)
#
#   tools/shot.sh tap X Y              input tap (device px, 1080x2400 screen)
#   tools/shot.sh swipe X1 Y1 X2 Y2 [duration_ms]   input swipe (default 300ms)
#   tools/shot.sh back                 keyevent 4 (BACK)
#   tools/shot.sh text "some words"    input text (spaces escaped as %s; NO CJK)
#   tools/shot.sh sleep N              sleep N seconds
#   tools/shot.sh status               adb serial / boot / current window / apk
#   tools/shot.sh list                 list available adb devices
#
# ENV OVERRIDES
#   ANDROID_SERIAL   pick a specific adb serial (default: first emulator)
#   SHOT_DIR         output directory (default <repo>/screenshots)
#   APK              APK path (default <repo>/app/build/outputs/apk/debug/app-debug.apk)
#   ADB              adb binary (default <SDK>/platform-tools/adb, then PATH)
#   SCREEN_WIDTH     downscale target width (default 900, 0 = keep full res)
#
# EXAMPLES
#   tools/shot.sh home                      # clean launch + shot
#   tools/shot.sh player --no-install --wait 6
#   tools/shot.sh tap 540 2000 && tools/shot.sh capture after-tap
#   tools/shot.sh swipe 540 1800 540 600 && tools/shot.sh capture scrolled
#
# NOTES / CAVEATS
#   * Device is 1080x2400 @ density 420 => divide dp by ~2.625 for px, or use
#     dp*2.625. Coordinates below are raw PIXELS.
#   * First launch after install is slow (swiftshader software GL): use --wait 6+.
#   * `input text` cannot type CJK — use Latin text or seed data via adb.
#   * Script always targets one explicit serial so it is safe with several AVDs.
# =============================================================================
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
PKG="dev.walden.mygomusic"
ACTIVITY="$PKG/.MainActivity"

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/home/hui/Android/Sdk}}"
if [[ -n "${ADB:-}" ]]; then
  ADB_BIN="$ADB"
elif [[ -x "$SDK/platform-tools/adb" ]]; then
  ADB_BIN="$SDK/platform-tools/adb"
elif command -v adb >/dev/null 2>&1; then
  ADB_BIN="$(command -v adb)"
else
  echo "ERROR: adb not found (looked at $SDK/platform-tools/adb and PATH)." >&2
  echo "       Set ADB=/path/to/adb or ANDROID_SDK_ROOT." >&2
  exit 1
fi

SHOT_DIR="${SHOT_DIR:-$REPO_ROOT/screenshots}"
APK="${APK:-$REPO_ROOT/app/build/outputs/apk/debug/app-debug.apk}"
SCREEN_WIDTH="${SCREEN_WIDTH:-900}"

die() { echo "ERROR: $*" >&2; exit 1; }
info() { printf '%s\n' "$*" >&2; }

# ---- adb device resolution -------------------------------------------------
SERIAL="${ANDROID_SERIAL:-}"
if [[ -z "$SERIAL" ]]; then
  # prefer an actual emulator over a physical device
  SERIAL="$("$ADB_BIN" devices 2>/dev/null | awk '$2=="device" && $1 ~ /^emulator-/ {print $1; exit}')"
  if [[ -z "$SERIAL" ]]; then
    SERIAL="$("$ADB_BIN" devices 2>/dev/null | awk '$2=="device" {print $1; exit}')"
  fi
fi
[[ -n "$SERIAL" ]] || die "no adb device attached. Boot the emulator first:
  $SDK/emulator/emulator -avd pin37 -no-window -gpu swiftshader_indirect -no-snapshot -no-audio -no-boot-anim &
then: $ADB_BIN wait-for-device"

adb() { "$ADB_BIN" -s "$SERIAL" "$@"; }

require_device() {
  local state
  state="$(adb get-state 2>/dev/null || true)"
  [[ "$state" == "device" ]] || die "adb serial '$SERIAL' is not ready (state='${state:-none}')."
  local boot
  boot="$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
  [[ "$boot" == "1" ]] || die "emulator '$SERIAL' not fully booted yet (sys.boot_completed='${boot:-0}'). Poll again."
}

unlock() {
  adb shell wm dismiss-keyguard >/dev/null 2>&1 || true
  adb shell input keyevent 82 >/dev/null 2>&1 || true   # MENU/unlock
}

# ---- screenshot ------------------------------------------------------------
# Captures to $1. Uses exec-out, falls back to shell+pull for old adbd builds.
capture() {
  local out="$1" tmp_remote="/sdcard/.shot_tmp.png"
  if adb exec-out screencap -p > "$out" 2>/dev/null && [[ -s "$out" ]] \
     && [[ "$(head -c 8 "$out" | od -An -tx1 | tr -d ' \n')" == "89504e470d0a1a0a" ]]; then
    :
  else
    info "exec-out screencap failed, retrying via shell+pull"
    rm -f "$out"
    adb shell screencap -p "$tmp_remote" >/dev/null 2>&1 || die "adb screencap failed on $SERIAL"
    adb pull "$tmp_remote" "$out" >/dev/null 2>&1 || die "adb pull failed on $SERIAL"
    adb shell rm -f "$tmp_remote" >/dev/null 2>&1 || true
  fi
  [[ -s "$out" ]] || die "screencap produced an empty file: $out"
}

downscale() {
  local f="$1" w="$2"
  [[ "$w" =~ ^[0-9]+$ ]] || return 0
  (( w > 0 )) || return 0
  local im=""
  if command -v magick >/dev/null 2>&1; then im="magick"
  elif command -v convert >/dev/null 2>&1; then im="convert"
  fi
  if [[ -z "$im" ]]; then
    info "note: ImageMagick not found — keeping full-resolution screenshot"
    return 0
  fi
  local tmp="$f.tmp.png"
  if "$im" "$f" -resize "${w}x>" -strip "$tmp" 2>/dev/null; then
    mv -f "$tmp" "$f"
  else
    rm -f "$tmp"
    info "note: ImageMagick resize failed — keeping full-resolution screenshot"
  fi
}

report() {
  local f="$1"
  local bytes abs
  bytes="$(stat -c %s "$f" 2>/dev/null || echo 0)"
  abs="$(cd "$(dirname "$f")" && pwd)/$(basename "$f")"
  [[ "$bytes" -gt 0 ]] || die "screenshot file is empty: $abs"
  printf 'serial=%s\n' "$SERIAL"
  printf 'path=%s\n' "$abs"
  printf 'size=%s bytes (%s)\n' "$bytes" "$(du -h "$f" | cut -f1)"
  printf '%s\n' "$abs"   # last line: bare absolute path, easy to $(...) capture
}

do_shot() {
  local name="$1"; shift
  local do_install=1 do_launch=1 wait_s=4
  while (( $# )); do
    case "$1" in
      --no-install) do_install=0; shift ;;
      --keep|--capture-only) do_launch=0; do_install=0; shift ;;
      --wait) [[ $# -ge 2 ]] || die "--wait needs a number"; wait_s="$2"; shift 2 ;;
      --wait=*) wait_s="${1#*=}"; shift ;;
      -*) die "unknown option: $1" ;;
      *) die "unexpected argument: $1 (only --no-install / --keep / --wait N allowed after <name>)" ;;
    esac
  done
  [[ -n "$name" ]] || die "screenshot name is required"
  [[ "$name" =~ ^[A-Za-z0-9._-]+$ ]] || die "name may only contain letters, digits, '.', '_', '-'"
  [[ "$wait_s" =~ ^[0-9]+$ ]] || die "--wait must be an integer number of seconds"
  require_device
  mkdir -p "$SHOT_DIR"

  if (( do_install )); then
    [[ -f "$APK" ]] || die "APK missing: $APK
       Build it first:  cd $REPO_ROOT && ./gradlew assembleDebug
       Or take a shot of whatever is on screen:  tools/shot.sh $name --no-install"
    info "installing $APK ..."
    local inst out
    out="$(adb install -r -g "$APK" 2>&1)" || { printf '%s\n' "$out" >&2; die "adb install failed"; }
    if grep -qi '^Failure' <<<"$out"; then printf '%s\n' "$out" >&2; die "adb install reported Failure"; fi
    info "$(tr -d '\r' <<<"$out" | tail -2 | tr '\n' ' ')"
  fi

  if (( do_launch == 0 )); then
    info "capture-only: not relaunching (current UI state)"
  elif adb shell pm path "$PKG" 2>/dev/null | grep -q package:; then
    info "launching $ACTIVITY ..."
    unlock
    adb shell am force-stop "$PKG" >/dev/null 2>&1 || true
    adb shell am start -n "$ACTIVITY" >/dev/null 2>&1 \
      || die "am start $ACTIVITY failed even though $PKG looks installed — check logcat"
  else
    info "note: $PKG is not installed — capturing whatever is currently on screen"
    unlock
  fi
  # wait for the activity to actually own the window (best effort, capped)
  local i
  for i in $(seq 1 40); do
    if adb shell dumpsys window 2>/dev/null | grep -q "mCurrentFocus.*$PKG"; then break; fi
    sleep 0.25
  done
  sleep "$wait_s"

  local out="$SHOT_DIR/$name.png"
  info "screencapping ..."
  capture "$out"
  downscale "$out" "$SCREEN_WIDTH"
  report "$out"
}

case "${1:-}" in
  ""|-h|--help|help)
    sed -n '2,50p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
    ;;
  tap)
    [[ $# -eq 3 ]] || die "usage: shot.sh tap X Y"
    require_device; unlock
    adb shell input tap "$2" "$3" >/dev/null || die "adb input tap failed"
    echo "tapped $2 $3"
    ;;
  swipe)
    [[ $# -ge 5 ]] || die "usage: shot.sh swipe X1 Y1 X2 Y2 [duration_ms]"
    dur="${6:-300}"
    require_device
    adb shell input swipe "$2" "$3" "$4" "$5" "$dur" >/dev/null || die "adb input swipe failed"
    echo "swiped $2,$3 -> $4,$5 in ${dur}ms"
    ;;
  back)
    require_device
    adb shell input keyevent 4 >/dev/null || die "adb input keyevent 4 failed"
    echo "back"
    ;;
  text)
    [[ $# -ge 2 ]] || die 'usage: shot.sh text "ASCII text only"'
    require_device; unlock
    payload="${*:2}"
    payload="${payload// /%s}"
    adb shell input text "$payload" >/dev/null || die "adb input text failed"
    echo "typed: ${*:2}"
    ;;
  capture)
    [[ $# -ge 2 ]] || die "usage: shot.sh capture <name> [--wait N]"
    name="${2}"; shift 2
    do_shot "$name" --keep "$@"
    ;;
  sleep)
    [[ $# -eq 2 ]] || die "usage: shot.sh sleep N"
    sleep "$2"
    echo "slept $2s"
    ;;
  status)
    echo "adb:        $ADB_BIN"
    echo "serial:     ${SERIAL:-<none>}"
    if [[ -n "$SERIAL" ]]; then
      echo "state:      $(adb get-state 2>/dev/null || echo unknown)"
      echo "boot:       $(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
      echo "sdk:        $(adb shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')"
      echo "focus:      $(adb shell dumpsys window 2>/dev/null | grep -E 'mCurrentFocus' | head -1 | sed 's/^ *//')"
      echo "app:        $(adb shell pm path "$PKG" 2>/dev/null | tr -d '\r' | head -1)"
    fi
    echo "apk:        $APK $([[ -f "$APK" ]] && echo "(exists, $(stat -c %s "$APK") bytes)" || echo "(MISSING)")"
    echo "shot dir:   $SHOT_DIR"
    ;;
  list)
    "$ADB_BIN" devices -l
    ;;
  *)
    do_shot "$@"
    ;;
esac
