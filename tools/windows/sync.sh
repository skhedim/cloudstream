#!/usr/bin/env bash
# Copies the repository to the Windows workspace (default C:\dev\kloudstream), without build outputs.
set -euo pipefail
repo=$(cd "$(dirname "$0")/../.." && pwd)
win_root=${WIN_ROOT:-'C:\dev\kloudstream'}
target=$(wslpath -u "$win_root")
mkdir -p "$target"
rsync -a --delete --exclude '.git/' --exclude '.gradle/' --exclude 'build/' --exclude '.kotlin/' \
    --exclude 'local.properties' --exclude '*.iml' --exclude '.idea/' "$repo/" "$target/"
echo "$win_root"
