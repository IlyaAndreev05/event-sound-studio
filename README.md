# Event Sound Studio

A local desktop sound library and event-to-effect player. Settings live in `~/.config/event-sound-studio/config.yaml`; built-in and imported WAV files stay beside that YAML as separate assets.

## Run

Use Java 25 and `./gradlew run`. Build a local Debian package with `./gradlew packageDeb`; it appears under `build/deb/`. Keyboard and mouse monitoring are opt-in per device; the app never records key sequences. Key repeats are ignored by default or can be throttled, and common media keys have individual event IDs. Read [docs](docs/) for configuration, permissions, API, and packaging details. The optional GNOME event extension is installed with `scripts/install-gnome-events.sh` and enabled separately in GNOME Extensions; enable the GNOME source in app settings only after installing the extension. It forwards generic event names without window titles or contents. Alt+Tab key events remain distinct from GNOME focus changes; an optional setting can suppress the GNOME focus sound within 750 ms after Alt+Tab and is off by default.

For a read-only Java Sound output inventory, run `./gradlew run --args='--diagnose-audio'` or `EventSoundStudio --diagnose-audio` from the installed package. This lists mixer capabilities without opening an output line or playing audio; it cannot prove which physical connector a system-default mixer reaches.

The sound library exports an individual WAV or a selected sound-set ZIP with its provenance dependencies; the generator can write its result directly to a standalone WAV file. Selected bindings can be exported as a ZIP that imports as a new profile. The Profiles page can also export a complete profile or create a full configuration backup. Import checks the versioned manifest, hashes, and paths. Profile/binding-set archives merge as inactive profiles, while a sound-set ZIP merges into the library; ID conflicts are remapped and current settings/source states are preserved. A full backup replaces the YAML and restores with keyboard, mouse, GNOME, and API sources disabled. Missing output names can be mapped explicitly to an available output; profile imports use portable output defaults.

Profiles can select their own Java Sound output and master volume; activating a profile changes those settings for event playback.

The Events & bindings screen manages named custom events and named sound bindings independently. One event can launch several bindings; bindings can be edited, copied, enabled per profile, and deleted with a one-step undo. Binding priority and cooldown are validated in YAML and exposed alongside per-binding audio controls.

Profiles can be assigned independently to keyboard, mouse/wheel, and system/API event sources. Each source follows the active profile unless an override is selected; per-voice output and master volume follow the chosen source profile.
