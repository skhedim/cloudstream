#!/usr/bin/env bash
# Provider survey on the desktop runtime (docs/catalog-setup.md): downloads the candidate extensions
# listed in <spec.json>, runs them over its corpus and writes the measures to the spec's `out` file.
# The extensions run inside the test JVM with your rights, as they would in the app.
# Usage: tools/desktop/provider-survey.sh path/to/spec.json
set -euo pipefail
spec=$(realpath "$1")
repo=$(cd "$(dirname "$0")/../.." && pwd)
cd "$repo"
[[ -f "${XDG_CONFIG_HOME:-$HOME/.config}/cloudstream/env.sh" ]] && source "${XDG_CONFIG_HOME:-$HOME/.config}/cloudstream/env.sh"
exec ./gradlew :kloudstream-desktop:jvmTest --tests '*ProviderSurveyLiveTest*' -Pkloudstream.survey="$spec" --console=plain
