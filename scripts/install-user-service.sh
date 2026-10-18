#!/bin/sh
set -eu

if [ "$(id -u)" -eq 0 ]; then echo 'Run this installer as the desktop user, without sudo.' >&2; exit 2; fi
if [ "$#" -ne 0 ]; then echo 'Usage: install-user-service.sh' >&2; exit 2; fi
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
source=/opt/eventsoundstudio/lib/systemd/user/eventsoundstudio.service
if [ ! -r "$source" ]; then source="$script_dir/../src/main/systemd/user/eventsoundstudio.service"; fi
if [ ! -r "$source" ]; then echo 'Packaged user service not found.' >&2; exit 1; fi
if [ ! -x /opt/eventsoundstudio/bin/EventSoundStudio ]; then echo 'Install the Event Sound Studio .deb before installing its user service.' >&2; exit 1; fi
unit_dir=${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user
target=$unit_dir/eventsoundstudio.service
if [ -L "$target" ] || { [ -e "$target" ] && ! cmp -s "$source" "$target"; }; then
    echo "Refusing to replace an existing customized unit: $target" >&2
    exit 1
fi
mkdir -p "$unit_dir"
install -m 0644 "$source" "$target"
systemctl --user daemon-reload
printf 'Installed %s without enabling or starting it. No input permissions were changed.\n' "$target"
printf 'For optional session autostart, explicitly run: systemctl --user enable --now eventsoundstudio.service\n'
printf 'Quit an already-running manual instance first: /opt/eventsoundstudio/bin/EventSoundStudio --quit\n'
printf 'If the user manager lacks the desktop display environment, import only the available display variables before starting the service: systemctl --user import-environment DISPLAY WAYLAND_DISPLAY XAUTHORITY\n'
