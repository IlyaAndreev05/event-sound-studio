#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
scratch="$(mktemp -d "${TMPDIR:-/tmp}/event-sound-studio-gnome.XXXXXXXX")"
trap 'rm -rf -- "$scratch"' EXIT

extension_source="$repo_root/gnome/event-sound-studio-events"
node --check "$extension_source/extension.js"
node "$repo_root/scripts/test-gnome-notification-policy.mjs"
gnome-extensions pack "$extension_source" --extra-source=notificationPolicy.js --force --out-dir "$scratch"
unzip -t "$scratch/event-sound-studio-events@ilyaandreev05.shell-extension.zip"

fake_bin="$scratch/fake-bin"
user_data="$scratch/user-data"
user_state="$scratch/user-state"
uuid='event-sound-studio-events@ilyaandreev05'
target="$user_data/gnome-shell/extensions/$uuid"
enabled="$scratch/enabled-extensions"
mkdir -p "$fake_bin" "$target"
: > "$enabled"
cat > "$fake_bin/gnome-extensions" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
case "${1:-}" in
    list) cat "$FAKE_GNOME_ENABLED" ;;
    enable) grep -Fxq "$2" "$FAKE_GNOME_ENABLED" || printf '%s\n' "$2" >> "$FAKE_GNOME_ENABLED" ;;
    disable) sed "/^$2$/d" "$FAKE_GNOME_ENABLED" > "$FAKE_GNOME_ENABLED.next"; mv "$FAKE_GNOME_ENABLED.next" "$FAKE_GNOME_ENABLED" ;;
    *) exit 2 ;;
esac
EOF
chmod +x "$fake_bin/gnome-extensions"
printf '{"uuid":"%s","version":1}\n' "$uuid" > "$target/metadata.json"
printf '%s\n' 'old event extension' > "$target/extension.js"
env PATH="$fake_bin:$PATH" XDG_DATA_HOME="$user_data" XDG_STATE_HOME="$user_state" FAKE_GNOME_ENABLED="$enabled" \
    bash "$repo_root/scripts/install-gnome-events.sh"
cmp "$extension_source/extension.js" "$target/extension.js"
cmp "$extension_source/notificationPolicy.js" "$target/notificationPolicy.js"
test -r "$target.pre-event-sound-studio/extension.js"
env PATH="$fake_bin:$PATH" XDG_DATA_HOME="$user_data" XDG_STATE_HOME="$user_state" FAKE_GNOME_ENABLED="$enabled" \
    bash "$repo_root/scripts/uninstall-gnome-events.sh"
grep -Fq 'old event extension' "$target/extension.js"
test -r "$target.eventsoundstudio-removed/extension.js"
if grep -Fxq "$uuid" "$enabled"; then
    echo 'Uninstall did not restore the originally disabled extension state' >&2
    exit 1
fi

echo 'GNOME event extension package/install/rollback smoke passed (fake extension CLI; no session changes)'
