#!/usr/bin/env bash
# Builds the KloudStream Windows installer from WSL: the jars (with Windows natives) are built here,
# then tools/windows/package.ps1 bundles mpv and runs jpackage and Inno Setup on Windows.
# Usage: KLOUDSTREAM_VERSION=1.0.0 tools/desktop/package-windows.sh   (needs WSL interop, JDK 17 and Inno Setup on Windows)
set -euo pipefail
repo=$(cd "$(dirname "$0")/../.." && pwd)
version=${KLOUDSTREAM_VERSION:-1.0.0}
win_build=${WIN_BUILD:-'C:\dev\kloudstream-build'}
cd "$repo"
[[ -f "${XDG_CONFIG_HOME:-$HOME/.config}/cloudstream/env.sh" ]] && source "${XDG_CONFIG_HOME:-$HOME/.config}/cloudstream/env.sh"
./gradlew :kloudstream-desktop:stageLibs -Pkloudstream.os=windows --console=plain -q
target=$(wslpath -u "$win_build")
mkdir -p "$target/input"
rsync -a --delete --exclude 'mpv/' kloudstream-desktop/build/stage/lib/ "$target/input/"
cp kloudstream-desktop/packaging/kloudstream.ico kloudstream-desktop/packaging/kloudstream.iss "$target/"
tools/windows/win.sh package -Build "$win_build" -Version "$version"
