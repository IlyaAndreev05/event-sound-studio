# Editing Event Sound Studio configuration

- `~/.config/event-sound-studio/config.yaml` is the only persistent settings source. Sound files remain separate files and YAML stores their paths and playback settings.
- The seven `builtin.*` groups, IDs, names, and member lists are protected. Edit their sound/bindings, not their membership.
- Validate with `./gradlew run --args="--validate-config /path/to/config.yaml"`.
- Output fields accept `system_default`, `profile_default` for bindings, or a selector copied from `--diagnose-audio`. Prefer those selectors over mixer names or enumeration positions. Legacy names work only when they identify exactly one mixer; unavailable or ambiguous outputs are never silently redirected.
- Keep `settings.input.device_allowlist` empty until the user explicitly opts into specific devices. Never add a user to `input`, log key sequences, or read window contents.
- The app uses atomic replacement and revisioned snapshots: a GUI save is rejected if its source snapshot predates an external edit, even if the file watcher has already reloaded that edit. Resolve the conflict from the latest YAML before retrying. Invalid YAML leaves the last known valid runtime configuration active and is not rewritten automatically.
- `source_profiles` may independently override `keyboard`, `mouse`, and `system` event sources with existing profile IDs. Remove a key to inherit `active_profile`; do not use node IDs as profile identifiers.
