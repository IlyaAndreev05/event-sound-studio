package io.github.ilyaandreev05.eventsoundstudio;

/** Keeps CLI controls and root refusal outside the JavaFX launcher bootstrap. */
public final class EventSoundStudioLauncher {
    private EventSoundStudioLauncher() { }

    public static void main(String[] args) {
        EventSoundStudioApp.main(args);
    }
}
