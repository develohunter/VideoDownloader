#!/usr/bin/env bash
set -euo pipefail

PACKAGE="com.example.videodownloader"

if ! command -v adb >/dev/null 2>&1; then
    for SDK in \
        "${ANDROID_SDK_ROOT:-}" \
        "${ANDROID_HOME:-}" \
        "/g/Android/Sdk" \
        "/c/Users/${USERNAME:-USER}/AppData/Local/Android/Sdk"
    do
        if [ -n "$SDK" ] && [ -x "$SDK/platform-tools/adb.exe" ]; then
            export PATH="$PATH:$SDK/platform-tools"
            break
        fi
    done
fi

if ! command -v adb >/dev/null 2>&1; then
    printf 'ERROR: adb was not found. Check your Android SDK path.\n'
    exit 1
fi

if ! adb get-state >/dev/null 2>&1; then
    printf 'ERROR: No Android device is connected through ADB.\n'
    exit 1
fi

APP_PID="$(adb shell pidof -s "$PACKAGE" 2>/dev/null | tr -d '\r' | awk 'NF {print $1; exit}')"

if ! [[ "$APP_PID" =~ ^[0-9]+$ ]]; then
    printf 'ERROR: VideoDownloader is not running. Open the app and try again.\n'
    exit 1
fi

mkdir -p reports

RAW="$(mktemp)"
trap 'rm -f "$RAW"' EXIT

REPORT="reports/logcat-latest.txt"

adb logcat -d -v threadtime --pid="$APP_PID" > "$RAW"

{
    printf '%s\n' '========== VIDEO DOWNLOADER PRIVATE DIAGNOSTIC REPORT =========='
    printf 'Date: %s\n' "$(date)"
    printf 'Package: %s\n' "$PACKAGE"
    printf '\n%s\n' '========== FILTERED APP LOGS =========='

    grep -Ei -B 4 -A 8 \
    'DL_REPORT|FFmpegKit|FFmpeg|FFprobe|yt_dlp|yt-dlp|DownloadForegroundService|ForegroundService|startForeground|stopForeground|FATAL EXCEPTION|AndroidRuntime|ANR in|Fatal signal|OutOfMemoryError|SecurityException|IllegalStateException|NullPointerException|TimeoutException|InterruptedException|IOException|Permission Denial|Foreground service did not start|RemoteServiceException|onTimeout|am_crash|am_anr|denied|timed out|failed|failure|exception|error|warning' \
    "$RAW" || true

} | sed -E \
    -e 's#https?://[^[:space:]"<>]+#[URL_REDACTED]#g' \
    -e 's/[[:alnum:]_.+-]+@[[:alnum:].-]+\.[[:alpha:]]{2,}/[EMAIL_REDACTED]/g' \
    -e 's/([0-9]{1,3}\.){3}[0-9]{1,3}/[IP_REDACTED]/g' \
    -e 's/((token|access_token|refresh_token|api_key|apikey|signature|sig|authorization|password|passwd|secret|cookie)[=:][[:space:]]*)[^&[:space:],;]+/\1[REDACTED]/Ig' \
    -e 's/(Authorization:[[:space:]]*(Bearer|Basic)[[:space:]]+)[^[:space:]]+/\1[REDACTED]/Ig' \
    -e 's/(Cookie:[[:space:]]*)[^[:space:]]+/\1[REDACTED]/Ig' \
    > "$REPORT"

printf '\nFiltered lines: %s\n' "$(wc -l < "$REPORT")" >> "$REPORT"

printf '\n===== PRIVATE REPORT CREATED =====\n'
printf 'REPORT=%s\n' "$REPORT"

clip.exe < "$REPORT"

if command -v cygpath >/dev/null 2>&1; then
    notepad "$(cygpath -w "$REPORT")"
fi
