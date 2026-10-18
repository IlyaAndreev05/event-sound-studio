package io.github.ilyaandreev05.eventsoundstudio.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Additional editable groups deliberately leave the protected groups unchanged. */
final class DefaultEventPresets {
    private DefaultEventPresets() {}

    static final List<String> PUNCTUATION = List.of("KEY_SPACE", "KEY_MINUS", "KEY_EQUAL", "KEY_LEFTBRACE",
            "KEY_RIGHTBRACE", "KEY_BACKSLASH", "KEY_SEMICOLON", "KEY_APOSTROPHE", "KEY_GRAVE", "KEY_COMMA", "KEY_DOT", "KEY_SLASH", "KEY_102ND");
    static final List<String> TECHNICAL = List.of("KEY_LEFTCTRL", "KEY_RIGHTCTRL", "KEY_LEFTALT", "KEY_RIGHTALT",
            "KEY_LEFTSHIFT", "KEY_RIGHTSHIFT", "KEY_TAB", "KEY_ESC", "KEY_INSERT", "KEY_HOME", "KEY_END",
            "KEY_PAGEUP", "KEY_PAGEDOWN", "KEY_UP", "KEY_DOWN", "KEY_LEFT", "KEY_RIGHT", "KEY_CAPSLOCK",
            "KEY_NUMLOCK", "KEY_SCROLLLOCK", "KEY_SYSRQ", "KEY_PAUSE", "KEY_MUTE", "KEY_VOLUMEDOWN",
            "KEY_VOLUMEUP", "KEY_PLAYPAUSE", "KEY_STOPCD", "KEY_PREVIOUSSONG", "KEY_NEXTSONG", "KEY_BRIGHTNESSDOWN", "KEY_BRIGHTNESSUP", "KEY_FN");
    static List<Map<String,Object>> groups() {
        List<String> function = new ArrayList<>();
        for (int i = 1; i <= 24; i++) function.add("KEY_F" + i);
        return List.of(group("custom.function", "F1–F24", function, "octine-click"),
                group("custom.technical", "Технические клавиши", TECHNICAL, "octine-technical"),
                group("custom.punctuation", "Пунктуация и пробел", PUNCTUATION, "octine-click"));
    }

    private static Map<String,Object> group(String id, String name, List<String> members, String sound) {
        return DefaultConfig.map("id", id, "name", name, "protected", false, "members", members, "default_sound", sound);
    }

    static void apply(Map<String,Object> root) {
        List<Map<String,Object>> events = new ArrayList<>(maps(root.get("event_types")));
        events.add(DefaultConfig.map("id", "backspace_press", "source", "evdev", "match", DefaultConfig.map("key", "KEY_BACKSPACE", "state", "press")));
        events.add(DefaultConfig.map("id", "delete_press", "source", "evdev", "match", DefaultConfig.map("key", "KEY_DELETE", "state", "press")));
        events.add(DefaultConfig.map("id", "notification_visible", "source", "gnome", "match", DefaultConfig.map("signal", "notification_visible")));
        root.put("event_types", events);
        maps(root.get("groups")).addAll(groups());
        List<Map<String,Object>> bindings = maps(root.get("bindings"));
        for (Map<String,Object> binding : bindings) {
            switch (String.valueOf(binding.get("id"))) {
                case "mouse-middle-click" -> setSound(binding, "octine-middle-click", 48);
                case "workspace-change" -> setSound(binding, "octine-workspace", 120);
                case "super-key-press", "super-right-key-press" -> setSound(binding, "octine-click", 60);
                default -> { }
            }
        }
        bindings.add(shortBinding("punctuation-default", "group:custom.punctuation", "octine-click", true, 0.07, 60));
        bindings.add(shortBinding("function-keys", "group:custom.function", "octine-click", true, 0.15, 60));
        bindings.add(shortBinding("technical-keys", "group:custom.technical", "octine-technical", false, 1.0, 28));
        bindings.add(shortBinding("backspace-key", "backspace_press", "octine-click", true, 0.07, 60));
        bindings.add(shortBinding("delete-key", "delete_press", "octine-click", true, 0.07, 60));
        bindings.add(shortBinding("notification-tap", "notification_visible", "octine-notification", true, 1.0, 85));
        for (Map<String,Object> profile : maps(root.get("profiles"))) {
            List<Object> enabled = new ArrayList<>((List<?>) profile.get("enabled_bindings"));
            if ("desk".equals(profile.get("id"))) {
                for (String id : List.of("punctuation-default", "function-keys", "backspace-key", "delete-key", "notification-tap")) if (!enabled.contains(id)) enabled.add(id);
            }
            profile.put("enabled_bindings", enabled);
        }
    }

    private static void setSound(Map<String,Object> binding, String sound, int duration) {
        binding.put("sound", sound); binding.put("duration_ms", duration);
    }

    private static Map<String,Object> shortBinding(String id, String event, String sound, boolean enabled, double volume, int duration) {
        Map<String,Object> binding = DefaultConfig.binding(id, event, sound, enabled, 30, volume, 0, "mix");
        binding.put("duration_ms", duration);
        return binding;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String,Object>> maps(Object value) { return (List<Map<String,Object>>) value; }
}
