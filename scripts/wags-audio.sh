#!/usr/bin/env bash
# wags-audio — download a YouTube video's audio into the Wags meditation audio folder.
#
# Usage:
#   wags-audio <youtube-url>              # a bare URL or the full YouTube "share" text
#   WAGS_AUDIO_DIR=/path wags-audio <url> # override destination folder
#
# The file lands in ~/noteVault/wags_audio (Syncthing pushes it to the phone),
# then shows up in Wags → Meditation → Choose Audio (tap Refresh).
#
# Requirements: yt-dlp (auto-installed via pipx/pip3 if missing).
# Optional:     ffmpeg — enables mp3 conversion + embedded thumbnail/metadata.
#               Without it the native .m4a stream is saved (Wags plays .m4a fine).

set -euo pipefail

DEST="${WAGS_AUDIO_DIR:-/home/twain/noteVault/wags_audio}"

# ── Extract the first YouTube URL from the argument (accepts full share text) ──
URL="$(printf '%s' "${1:-}" | grep -oE \
  'https?://([a-zA-Z0-9-]+\.)*(youtube\.com/(watch\?[^ &]+|shorts/[^ &]+|embed/[^ &]+|live/[^ &]+)|youtu\.be/[^ &]+|music\.youtube\.com/watch\?[^ &]+)' \
  | head -n1 || true)"
# Trim only TRAILING punctuation pasted along with the URL (e.g. "…/abcId.,)")
URL="${URL%"${URL##*[!.,;)]}"}"

if [[ -z "$URL" ]]; then
    echo "Usage: wags-audio <youtube-url>" >&2
    echo "Example: wags-audio https://youtu.be/ABC123xyz" >&2
    exit 1
fi

# ── Ensure yt-dlp exists ──────────────────────────────────────────────────────
if ! command -v yt-dlp >/dev/null 2>&1; then
    echo "yt-dlp not found — installing…" >&2
    if command -v pipx >/dev/null 2>&1; then
        pipx install yt-dlp
    elif command -v pip3 >/dev/null 2>&1; then
        pip3 install --user yt-dlp
    else
        echo "Could not auto-install yt-dlp. See: https://github.com/yt-dlp/yt-dlp#installation" >&2
        exit 1
    fi
fi

mkdir -p "$DEST"
echo "⬇  $URL"

download() {
    if command -v ffmpeg >/dev/null 2>&1; then
        # ffmpeg available → extract to mp3 with metadata + thumbnail
        yt-dlp --no-update \
            --extract-audio --audio-format mp3 --audio-quality 0 \
            --embed-thumbnail --add-metadata \
            --no-mtime --trim-filenames 120 \
            --output "$DEST/%(title)s.%(ext)s" \
            "$URL"
    else
        # No ffmpeg → keep the native m4a audio stream as-is
        echo "⚠  ffmpeg not found — saving native .m4a without mp3 conversion." >&2
        yt-dlp --no-update \
            --format "bestaudio[ext=m4a]/bestaudio" \
            --no-mtime --trim-filenames 120 \
            --output "$DEST/%(title)s.%(ext)s" \
            "$URL"
    fi
}

# YouTube changes often break old yt-dlp versions — if the download fails,
# update yt-dlp (works for the standalone binary / pip installs) and retry once.
if ! download; then
    echo "⚠  Download failed — updating yt-dlp and retrying…" >&2
    yt-dlp -U >/dev/null 2>&1 || true
    download
fi

echo "✓  Saved to $DEST — Syncthing will sync it to your phone."
echo "   In Wags: Meditation → Choose Audio → Refresh."
