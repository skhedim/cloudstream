#!/usr/bin/env bash
# Runs tools/windows/<script>.ps1 from the Windows workspace: win.sh screenshot -Out C:\tmp\a.png
set -euo pipefail
win_root=${WIN_ROOT:-'C:\dev\kloudstream'}
script=$1; shift
# Only the scripts are refreshed here; run sync.sh for the whole repository.
here=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$(wslpath -u "$win_root")/tools/windows"
rsync -a "$here/" "$(wslpath -u "$win_root")/tools/windows/"
powershell.exe -NoProfile -File "$win_root\\tools\\windows\\$script.ps1" "$@" | tr -d '\r'
