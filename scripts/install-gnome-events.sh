#!/bin/sh
set -eu

uuid=event-sound-studio-events@ilyaandreev05
data_home=${XDG_DATA_HOME:-$HOME/.local/share}
state_home=${XDG_STATE_HOME:-$HOME/.local/state}
target="$data_home/gnome-shell/extensions/$uuid"
state_dir="$state_home/event-sound-studio/gnome-extension"
backup="$target.pre-event-sound-studio"
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
package_source="/opt/eventsoundstudio/lib/gnome-shell/extensions/$uuid"
repo_root=$(CDPATH= cd -- "$script_dir/.." && pwd)
source_dir=$package_source
if [ ! -r "$source_dir/extension.js" ]; then source_dir="$repo_root/gnome/event-sound-studio-events"; fi

if [ ! -r "$source_dir/extension.js" ] || [ ! -r "$source_dir/metadata.json" ]; then
    echo "Event Sound Studio GNOME extension files are missing." >&2
    exit 1
fi
if [ -e "$state_dir/previous-state" ]; then
    echo "An Event Sound Studio extension backup already exists; uninstall it before replacing it." >&2
    exit 1
fi
if [ -e "$backup" ]; then echo "Backup path already exists: $backup" >&2; exit 1; fi

mkdir -p "$(dirname "$target")" "$state_dir"
enabled_state=unknown
if command -v gnome-extensions >/dev/null 2>&1; then
    if gnome-extensions list --enabled | grep -Fxq "$uuid"; then enabled_state=enabled; else enabled_state=disabled; fi
fi
if [ -e "$target" ]; then mv "$target" "$backup"; previous_state=present; else previous_state=absent; fi
tmp="$target.install.$$"
restore_on_error() {
    result=$?
    if [ "$result" -ne 0 ]; then
        rm -rf -- "$tmp"
        if [ -e "$backup" ] && [ ! -e "$target" ]; then mv "$backup" "$target"; fi
    fi
    exit "$result"
}
trap restore_on_error EXIT HUP INT TERM
mkdir "$tmp"
install -m 0644 "$source_dir/metadata.json" "$tmp/metadata.json"
install -m 0644 "$source_dir/extension.js" "$tmp/extension.js"
mv "$tmp" "$target"
printf '%s\n' "$previous_state" > "$state_dir/previous-state"
printf '%s\n' "$enabled_state" > "$state_dir/previous-enabled"
trap - EXIT HUP INT TERM
printf 'Installed %s. Previous files are backed up at %s.\n' "$uuid" "$backup"
printf 'This script does not enable the extension. Enable it with: gnome-extensions enable %s\n' "$uuid"
printf 'GNOME events contain generic event names only; window titles and contents are not sent.\n'
