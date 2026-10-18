# Octine Studio implementation report — Event Sound Studio

Branch: `feat/octine-haptics-event-routing`  
Baseline saved on `main` and merged to `develop`: `1569e73`.

## Implemented

- Persistent event PCM remains open for the lifetime of the process, including idle silence. The PipeWire target is still the isolated `audiomatrix_events` sink with no hardware fallback.
- Its own `eventsoundstudio_pcm` stream is explicitly unmuted and set to 100% after link verification; a saved 37% stream gain no longer makes events quiet. Normalization retries while the PipeWire registry catches up and never prevents the stream from staying alive.
- Packet-aware wheel normalization: `SYN_REPORT` commits each packet, `REL_WHEEL_HI_RES` accumulates 120 units per detent, legacy events are deduplicated, per-device/axis remainders are kept, and `SYN_DROPPED`/unplug resets state. Calibration snapshots count accepted event impulses separately from physical rendering.
- Authorized devices remain the Compx X2 mouse and NuPhy Air75 V2 keyboard interfaces. The live allowlist was reduced accordingly; arbitrary interfaces are not read. `log_key_sequences` remains false.
- Event input and GNOME adapters report aggregate diagnostics: raw/received events, binding matches, accepted requests, PCM queue/played/dropped counters, output lifecycle and stream state. No key sequence or notification content is stored.
- GNOME adapter adds a version-gated visible-banner notification source for GNOME 50 with DND/lock/source policy; unsupported versions report unsupported rather than inventing an event.
- Background lifecycle: `--background`, single-instance open forwarding, `--quit`, window hide/continue-audio, and a packaged user service with reversible install/uninstall helpers.
- Fixed an evdev shutdown deadlock: virtual-thread interruption closed the channel while holding the service monitor and blocked clean quit. Reads now use daemon platform threads; shutdown no longer blocks the JavaFX thread.
- Preferred haptic character imported from `~/Desktop/Octine_Studio/01_haptic` for left/right/middle click, scroll, keypress, Enter, Backspace and double-click, normalized to a safe digital peak. The generator script does not overwrite these imported assets.
- Existing sound library, bindings, profiles, WAV/ZIP import/export, generator and editor remain in use; no unrelated rewrite.

## Verification

- `./gradlew test --rerun-tasks --no-build-cache` passes; class-size gate passes (77 sources, max 200 code lines).
- Live physical input opened only `event2` (Compx mouse), `event6` and `event7` (NuPhy keyboard). A captured physical session produced 113 detected event/audio pulse groups.
- Physical click and wheel latency measured from evdev to analog monitor PCM: approximately **0.38 s** consistently, with the capture-monitor path included. There was no 9.5 s delay in the event-to-PCM path; the long gaps observed earlier came from Audio Matrix route reconstruction.
- Visible GNOME notification during a continuous event pulse train did not create a multi-second event gap after the Matrix routing change.
- Clean `--quit` completed successfully after the evdev fix.
- Event path measurement: analog Vibro output received clicks; HDMI remained at zero.

## Known limitations

- The installed GNOME extension must be updated and GNOME Shell restarted/session re-logged for the new notification policy; the old extension emits workspace/window events only.
- The temporary `eventN` identity of the virtual test device is intentionally rejected (`disabled-or-unsupported-interface`); physical authorized devices are used instead.
- Bluetooth headphones were not available during verification.
- Installed package: Event Sound Studio `0.2.2`; `eventsoundstudio.service` is installed and active. The package's new GNOME extension files include `notificationPolicy.js`; a Wayland session re-login is still required for GNOME Shell to load the new extension code.
- Feature commit: `6c08166`; merge into `develop`: `2572a82`; branch pushed to `origin/feat/octine-haptics-event-routing`.
- A 30-minute continuous run was started on 2026-10-18 17:53 MSK; its result is appended below when finished.
- Physical loudness is subjective; the user reported the first imported level as too strong, so the default Vibro gain was lowered to 0.6.
