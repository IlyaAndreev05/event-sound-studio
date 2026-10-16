#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
uuid="event-sound-studio-events@ilyaandreev05"
target="${XDG_DATA_HOME:-$HOME/.local/share}/gnome-shell/extensions/$uuid"
mkdir -p "$target"
cp "$repo_root/gnome/event-sound-studio-events/metadata.json" "$target/metadata.json"
cp "$repo_root/gnome/event-sound-studio-events/extension.js" "$target/extension.js"
printf 'Installed %s. Enable it with: gnome-extensions enable %s\n' "$uuid" "$uuid"
printf 'GNOME events are generic names only; window titles and contents are never sent.\n'
