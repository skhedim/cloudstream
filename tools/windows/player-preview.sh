#!/usr/bin/env bash
# Previews KloudStream's video player on Windows: the installed mpv and uosc, with the settings in
# kloudstream-desktop/packaging/mpv, on a generated test clip; copies a PNG of each state to <dir>.
#   tools/windows/player-preview.sh <dir> [-Windowed] [-Mpv C:\path\to\mpv.exe]
# The player opens full screen for about 15 seconds, then closes by itself. Nothing is streamed.
set -euo pipefail
out=${1:?usage: player-preview.sh <dir> [-Windowed] [-Mpv path]}; shift
here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../.." && pwd)
preview_win=${PREVIEW_ROOT:-'C:\dev\kloudstream-preview'}
preview=$(wslpath -u "$preview_win")
mkdir -p "$preview" "$out"

# 1 h 30 of a still, film-like picture with French and English audio (silent) and subtitles; made again
# when this script changes.
clip=$preview/clip.mkv
if [[ ! -f $clip || $0 -nt $clip ]]; then
    cat > "$preview/fr.srt" <<'EOF'
1
00:38:00,000 --> 00:38:40,000
Je n'aurais jamais cru te revoir ici,
après tout ce temps.
EOF
    cat > "$preview/en.srt" <<'EOF'
1
00:38:00,000 --> 00:38:40,000
I never thought I'd see you here again,
after all this time.
EOF
    ffmpeg -loglevel error -y -f lavfi -i 'gradients=s=1920x1080:c0=0x0e1621:c1=0x3b5b78:c2=0xd99a5b:c3=0xf4dcb0:nb_colors=4:x0=200:y0=0:x1=1500:y1=1080:seed=3' \
        -vf 'noise=alls=10:allf=u,vignette=PI/4' -frames:v 1 "$preview/frame.png"
    ffmpeg -loglevel error -y -loop 1 -framerate 1 -i "$preview/frame.png" \
        -f lavfi -i 'anullsrc=r=48000:cl=stereo' -f lavfi -i 'anullsrc=r=48000:cl=stereo' \
        -i "$preview/fr.srt" -i "$preview/en.srt" -map 0:v -map 1:a -map 2:a -map 3:s -map 4:s -t 5400 \
        -c:v libx264 -preset ultrafast -tune stillimage -g 30 -pix_fmt yuv420p -c:a libopus -b:a 6k -c:s srt \
        -metadata:s:a:0 language=fre -metadata:s:a:0 title='Français' \
        -metadata:s:a:1 language=eng -metadata:s:a:1 title='English' \
        -metadata:s:s:0 language=fre -metadata:s:s:0 title='Français' \
        -metadata:s:s:1 language=eng -metadata:s:s:1 title='English' "$preview/clip.part.mkv"
    mv "$preview/clip.part.mkv" "$clip"
fi

rsync -a --delete "$repo/kloudstream-desktop/packaging/mpv/" "$preview/settings/"
status=0
"$here/win.sh" player-preview -Video "$preview_win\\clip.mkv" -Settings "$preview_win\\settings" \
    -Out "$preview_win\\shots" "$@" || status=$?
# mpv's errors and warnings (its log file is verbose).
grep -a -E '\]\[(e|w)\]' "$preview/shots/mpv.log" 2>/dev/null | tail -n 15 || true
cp "$preview"/shots/*.png "$out"/ 2>/dev/null || true
ls "$out"
exit $status
