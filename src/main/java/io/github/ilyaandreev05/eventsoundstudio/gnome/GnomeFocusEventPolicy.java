package io.github.ilyaandreev05.eventsoundstudio.gnome;

/** Optional suppression for a GNOME focus notification immediately following an Alt+Tab key chord. */
public final class GnomeFocusEventPolicy {
    public static final long ALT_TAB_WINDOW_MS = 750;

    private GnomeFocusEventPolicy() {}

    public static boolean shouldSuppress(String event, boolean enabled, boolean recentAltTab) {
        return enabled && recentAltTab && "window_focus_changed".equals(event);
    }
}
