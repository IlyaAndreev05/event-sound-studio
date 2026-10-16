# Editing Event Sound Studio configuration

- `~/.config/event-sound-studio/config.json` is the only persistent settings source. Legacy `config.yaml` is imported once into JSON and preserved as `config.yaml.before-json`. Sound files remain separate assets; JSON stores their paths and playback settings.
- The seven `builtin.*` groups, IDs, names, and member lists are protected. Edit their sound/bindings, not their membership.
- Validate with `./gradlew run --args="--validate-config /path/to/config.json"`. Legacy YAML validation migrates in memory before checking.
- Event output is fixed to the isolated `audio_matrix_events` PipeWire sink. Preview and generator playback use `settings.preview_output`. Do not direct event bindings or profiles to physical outputs; final event routing belongs in Audio Matrix.
- Keep `settings.input.device_allowlist` empty until the user explicitly opts into specific devices. Never add a user to `input`, log key sequences, or read window contents.
- The app uses atomic replacement and revisioned snapshots: a GUI save is rejected if its source snapshot predates an external edit, even if the file watcher has already reloaded that edit. Resolve the conflict from the latest JSON before retrying. Invalid JSON leaves the last known valid runtime configuration active and is not rewritten automatically.
- `source_profiles` may independently override `keyboard`, `mouse`, and `system` event sources with existing profile IDs. Remove a key to inherit `active_profile`; do not use node IDs as profile identifiers.
