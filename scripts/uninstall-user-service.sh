#!/bin/sh
set -eu

if [ "$(id -u)" -eq 0 ]; then echo 'Run this uninstaller as the desktop user, without sudo.' >&2; exit 2; fi
if [ "$#" -ne 0 ]; then echo 'Usage: uninstall-user-service.sh' >&2; exit 2; fi
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
source=/opt/eventsoundstudio/lib/systemd/user/eventsoundstudio.service
if [ ! -r "$source" ]; then source="$script_dir/../src/main/systemd/user/eventsoundstudio.service"; fi
target=${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user/eventsoundstudio.service
if [ ! -e "$target" ] && [ ! -L "$target" ]; then echo 'No installed Event Sound Studio user unit found.'; exit 0; fi
if [ -L "$target" ] || [ ! -r "$source" ] || ! cmp -s "$source" "$target"; then
    echo "Refusing to remove a customized or unrecognized unit: $target. Stop/disable it and remove it manually if intended." >&2
    exit 1
fi
systemctl --user disable --now eventsoundstudio.service
rm -- "$target"
systemctl --user daemon-reload
printf 'Removed %s. Application configuration, GNOME integration and input permissions were left unchanged.\n' "$target"
