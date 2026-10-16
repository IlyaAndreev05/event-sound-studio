#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
test_config_dir="$(mktemp -d "${TMPDIR:-/tmp}/event-sound-studio-alsa-null.XXXXXXXX")"
trap 'rm -rf -- "$test_config_dir"' EXIT

cat > "$test_config_dir/alsa.conf" <<'EOF'
pcm.null {
  type null
}
pcm.!default {
  type file
  slave.pcm "null"
  file "/dev/null"
  format "raw"
}
EOF

cd "$repo_root"
EVENT_SOUND_STUDIO_ALSA_NULL_SMOKE=1 \
ALSA_CONFIG_PATH="$test_config_dir/alsa.conf" \
./gradlew test --no-configuration-cache --no-daemon \
  --tests io.github.ilyaandreev05.eventsoundstudio.audio.AlsaNullOutputSmokeTest
