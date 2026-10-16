package io.github.ilyaandreev05.eventsoundstudio.gnome;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GnomeFocusEventPolicyTest {
    @Test void focusDeduplicationIsOptInAndOnlySuppressesRecentAltTabFocusEvents() {
        assertFalse(GnomeFocusEventPolicy.shouldSuppress("window_focus_changed", false, true));
        assertFalse(GnomeFocusEventPolicy.shouldSuppress("window_focus_changed", true, false));
        assertFalse(GnomeFocusEventPolicy.shouldSuppress("workspace_changed", true, true));
        assertTrue(GnomeFocusEventPolicy.shouldSuppress("window_focus_changed", true, true));
    }
}
