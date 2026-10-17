package io.github.ilyaandreev05.eventsoundstudio.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DefaultConfig {
    private DefaultConfig() {}
    public static final Map<String, List<String>> BUILTIN_GROUPS = groups();
    public static final Map<String, String> BUILTIN_NAMES = names();

    public static Map<String, Object> create() {
        Map<String, Object> root = map("schema_version", 4, "app", "event-sound-studio", "active_profile", "desk",
                "graph_routes", new ArrayList<>(), "route_groups", new ArrayList<>(), "saved_copies", new ArrayList<>());
        root.put("source_profiles", map());
        root.put("settings", map("language", "ru", "theme", "system", "master_volume", 0.65, "master_mute", false,
                "output", io.github.ilyaandreev05.eventsoundstudio.audio.MixerOutputSelector.AUDIO_MATRIX_EVENTS,
                "preview_output", "system_default", "background_mode", "continue_audio", "overlap_policy", "mix_with_limiter",
                "default_cooldown_ms", 20, "mouse_click_policy", map("mode", "immediate_plus_double", "double_click_window_ms", 250),
                "keyboard_repeat", map("mode", "ignore", "minimum_interval_ms", 180),
                "input", map("enable_keyboard", false, "enable_mouse", false, "enable_gnome", false,
                        "deduplicate_alt_tab_focus", false, "device_allowlist", new ArrayList<>(), "log_key_sequences", false),
                "wheel", map("mode", "one_pulse_per_detent", "high_resolution_preferred", true,
                        "units_per_detent", 120, "legacy_deduplication", true,
                        "calibration", map("observed_detents", 0, "observed_units", 0))));
        List<Map<String, Object>> sounds = new ArrayList<>();
        for (SoundAsset sound : builtInSounds()) sounds.add(map("id", sound.id(), "name", sound.name(),
                "path", "assets/sounds/" + sound.id() + ".wav", "format", "pcm_wav", "volume", 0.7,
                "tags", sound.tags(), "favorite", false, "builtin", true));
        root.put("sounds", sounds);
        root.put("event_types", List.of(
                map("id", "key_press", "source", "evdev", "match", map("kind", "key", "state", "press")),
                map("id", "enter_press", "source", "evdev", "match", map("key", "KEY_ENTER", "state", "press")),
                map("id", "mouse_left", "source", "evdev", "match", map("button", "BTN_LEFT", "state", "press")),
                map("id", "mouse_double_left", "source", "derived", "match", map("button", "BTN_LEFT", "click_count", 2, "window_ms", 250)),
                map("id", "wheel_up", "source", "evdev", "match", map("axis", "vertical", "direction", "up", "unit", "detent")),
                map("id", "wheel_down", "source", "evdev", "match", map("axis", "vertical", "direction", "down", "unit", "detent")),
                map("id", "wheel_left", "source", "evdev", "match", map("axis", "horizontal", "direction", "left", "unit", "detent")),
                map("id", "wheel_right", "source", "evdev", "match", map("axis", "horizontal", "direction", "right", "unit", "detent")),
                map("id", "combo_save", "source", "evdev", "match", map("keys", List.of("KEY_LEFTCTRL", "KEY_S"), "policy", "ONLY_COMBO")),
                map("id", "desktop_changed", "source", "gnome", "match", map("signal", "workspace_changed")),
                map("id", "window_focus_changed", "source", "gnome", "match", map("signal", "window_focus_changed")),
                map("id", "window_opened", "source", "gnome", "match", map("signal", "window_opened")),
                map("id", "window_closed", "source", "gnome", "match", map("signal", "window_closed")),
                map("id", "api_test", "source", "local_api", "match", map("event_id", "test"))));
        List<Map<String, Object>> groups = new ArrayList<>();
        for (var entry : BUILTIN_GROUPS.entrySet()) groups.add(map("id", entry.getKey(), "name", BUILTIN_NAMES.get(entry.getKey()),
                "protected", true, "members", entry.getValue(), "default_sound", defaultSound(entry.getKey())));
        groups.add(map("id", "custom.game", "name", "Игра", "protected", false,
                "members", List.of("KEY_W", "KEY_A", "KEY_S", "KEY_D"), "default_sound", "key-tap"));
        root.put("groups", groups);
        List<Map<String, Object>> bindings = new ArrayList<>();
        bindings.add(binding("all-key-press", "key_press", "key-tap", true, 5, 0.45, 0, "mix"));
        bindings.add(binding("letters-default", "group:builtin.letters", "key-tap", true, 10, 0.5, 0, "mix"));
        bindings.add(binding("digits-default", "group:builtin.digits", "key-tap-low", true, 10, 0.45, 0, "mix"));
        bindings.add(binding("enter-key", "enter_press", "soft-click", true, 30, 0.6, 0, "mix"));
        bindings.add(binding("mouse-click", "mouse_left", "soft-click", true, 20, 0.55, 15, "mix"));
        bindings.add(binding("mouse-double-click", "mouse_double_left", "notification-one", true, 80, 0.35, 100, "mix"));
        bindings.add(binding("wheel-up", "wheel_up", "wheel-tick", true, 20, 0.5, 10, "restart"));
        bindings.add(binding("wheel-down", "wheel_down", "wheel-tick", true, 20, 0.5, 10, "restart"));
        bindings.add(binding("wheel-left", "wheel_left", "wheel-tick", true, 20, 0.5, 10, "restart"));
        bindings.add(binding("wheel-right", "wheel_right", "wheel-tick", true, 20, 0.5, 10, "restart"));
        bindings.add(binding("save-combo", "combo_save", "notification-two", true, 100, 0.4, 100, "ignore"));
        bindings.add(binding("workspace-change", "desktop_changed", "notification-one", true, 50, 0.3, 250, "ignore"));
        bindings.add(binding("super-key-press", "key:KEY_LEFTMETA:press", "soft-pop", true, 60, 0.35, 120, "ignore"));
        bindings.add(binding("super-right-key-press", "key:KEY_RIGHTMETA:press", "soft-pop", true, 60, 0.35, 120, "ignore"));
        bindings.stream().filter(binding -> List.of("all-key-press","letters-default","mouse-click").contains(binding.get("id"))).forEach(binding -> binding.put("duration_ms", 45));
        bindings.stream().filter(binding -> String.valueOf(binding.get("id")).startsWith("wheel-")).forEach(binding -> binding.put("duration_ms", 28));
        bindings.stream().filter(binding -> "workspace-change".equals(binding.get("id"))).forEach(binding -> binding.put("duration_ms", 450));
        bindings.stream().filter(binding -> List.of("super-key-press","super-right-key-press").contains(binding.get("id"))).forEach(binding -> binding.put("duration_ms", 220));
        bindings.add(binding("window-focus", "window_focus_changed", "subtle-confirm", true, 40, 0.25, 180, "ignore"));
        bindings.add(binding("window-open", "window_opened", "subtle-confirm", false, 35, 0.2, 200, "ignore"));
        bindings.add(binding("window-close", "window_closed", "subtle-cancel", false, 35, 0.2, 200, "ignore"));
        bindings.add(binding("custom-test-api", "api_test", "key-tap", true, 1, 0.5, 0, "mix"));
        root.put("bindings", bindings);
        root.put("combos", List.of(map("id", "save", "keys", List.of("KEY_LEFTCTRL", "KEY_S"), "policy", "ONLY_COMBO", "event", "combo_save")));
        root.put("profiles", List.of(
                map("id", "desk", "name", "Рабочий стол", "enabled_bindings", List.of("all-key-press", "letters-default", "digits-default", "enter-key", "mouse-click", "mouse-double-click", "wheel-up", "wheel-down", "wheel-left", "wheel-right", "save-combo", "workspace-change", "window-focus", "super-key-press", "super-right-key-press", "custom-test-api"), "output", io.github.ilyaandreev05.eventsoundstudio.audio.MixerOutputSelector.AUDIO_MATRIX_EVENTS, "master_volume", 0.65),
                map("id", "quiet", "name", "Тихий режим", "enabled_bindings", List.of("custom-test-api"), "output", io.github.ilyaandreev05.eventsoundstudio.audio.MixerOutputSelector.AUDIO_MATRIX_EVENTS, "master_volume", 0.25)));
        root.put("api", map("enabled", false));
        return root;
    }

    public static List<SoundAsset> builtInSounds() {
        return List.of(
                new SoundAsset("soft-click", "Мягкий клик", List.of("мягкий", "кнопки")),
                new SoundAsset("soft-pop", "Мягкий щелчок", List.of("мягкий")),
                new SoundAsset("key-tap", "Короткий клавишный щелчок", List.of("клавиши", "короткий")),
                new SoundAsset("key-tap-low", "Низкий клавишный щелчок", List.of("клавиши")),
                new SoundAsset("wood-click", "Деревянный щелчок", List.of("дерево")),
                new SoundAsset("wood-knock", "Деревянный стук", List.of("дерево", "низкий")),
                new SoundAsset("mechanical-tick", "Механический тик", List.of("механика")),
                new SoundAsset("mechanical-clack", "Механический щелчок", List.of("механика")),
                new SoundAsset("wheel-tick", "Деление колеса", List.of("колесо", "тактильный")),
                new SoundAsset("wheel-soft", "Мягкое деление колеса", List.of("колесо")),
                new SoundAsset("bass-thump", "Низкий удар", List.of("бас", "низкий")),
                new SoundAsset("bass-pulse", "Басовый импульс", List.of("бас")),
                new SoundAsset("bass-knock", "Басовый стук", List.of("бас")),
                new SoundAsset("electronic-blip", "Электронный сигнал", List.of("электронный")),
                new SoundAsset("electronic-high", "Высокий электронный сигнал", List.of("электронный")),
                new SoundAsset("electronic-low", "Низкий электронный сигнал", List.of("электронный")),
                new SoundAsset("chirp-up", "Восходящий чирп", List.of("свип")),
                new SoundAsset("chirp-down", "Нисходящий чирп", List.of("свип")),
                new SoundAsset("notification-one", "Уведомление: один тон", List.of("уведомление")),
                new SoundAsset("notification-two", "Уведомление: два тона", List.of("уведомление")),
                new SoundAsset("notification-three", "Уведомление: три тона", List.of("уведомление")),
                new SoundAsset("subtle-confirm", "Подтверждение", List.of("подтверждение")),
                new SoundAsset("subtle-cancel", "Отмена", List.of("отмена")),
                new SoundAsset("soft-alert", "Мягкое предупреждение", List.of("уведомление")),
                new SoundAsset("wooden-tick", "Деревянный тик", List.of("дерево")),
                new SoundAsset("wooden-pop", "Деревянный щелчок", List.of("дерево")),
                new SoundAsset("ceramic-click", "Керамический щелчок", List.of("короткий")),
                new SoundAsset("rubber-tap", "Резиновый тап", List.of("мягкий")),
                new SoundAsset("glass-tick", "Стеклянный тик", List.of("высокий")),
                new SoundAsset("paper-tap", "Бумажный тап", List.of("короткий")),
                new SoundAsset("click-round", "Округлый клик", List.of("кнопки")),
                new SoundAsset("click-crisp", "Чёткий клик", List.of("кнопки")),
                new SoundAsset("muted-thud", "Приглушённый удар", List.of("низкий")),
                new SoundAsset("short-impulse", "Короткий импульс", List.of("импульс")),
                new SoundAsset("rise-sweep", "Восходящий sweep", List.of("свип")),
                new SoundAsset("fall-sweep", "Нисходящий sweep", List.of("свип")));
    }

    public static Map<String, Object> binding(String id, String event, String sound, boolean enabled, int priority, double volume, int cooldown, String overlap) {
        return map("id", id, "event", event, "sound", sound, "enabled", enabled, "priority", priority,
                "volume", volume, "cooldown_ms", cooldown, "overlap", overlap, "output", "profile_default",
                "eq", map("enabled", false, "filters", List.of()), "attack_ms", 0, "release_ms", 0,
                "duration_ms", 0, "remove_dc", false);
    }
    private static String defaultSound(String group) {
        return switch (group) {
            case "builtin.function", "builtin.modifiers", "builtin.mouse" -> "soft-click";
            case "builtin.navigation" -> "key-tap";
            default -> "key-tap";
        };
    }
    private static Map<String, List<String>> groups() {
        Map<String, List<String>> g = new LinkedHashMap<>();
        g.put("builtin.letters", range("KEY_", "KEY_Z", 30));
        g.put("builtin.digits", List.of("KEY_0","KEY_1","KEY_2","KEY_3","KEY_4","KEY_5","KEY_6","KEY_7","KEY_8","KEY_9"));
        g.put("builtin.function", List.of("KEY_F1","KEY_F2","KEY_F3","KEY_F4","KEY_F5","KEY_F6","KEY_F7","KEY_F8","KEY_F9","KEY_F10","KEY_F11","KEY_F12"));
        g.put("builtin.modifiers", List.of("KEY_LEFTCTRL","KEY_RIGHTCTRL","KEY_LEFTALT","KEY_RIGHTALT","KEY_LEFTSHIFT","KEY_RIGHTSHIFT","KEY_LEFTMETA","KEY_RIGHTMETA"));
        g.put("builtin.navigation", List.of("KEY_ENTER","KEY_SPACE","KEY_TAB","KEY_ESC","KEY_BACKSPACE","KEY_DELETE","KEY_INSERT","KEY_HOME","KEY_END","KEY_PAGEUP","KEY_PAGEDOWN","KEY_UP","KEY_DOWN","KEY_LEFT","KEY_RIGHT"));
        g.put("builtin.numpad", List.of("KEY_KP0","KEY_KP1","KEY_KP2","KEY_KP3","KEY_KP4","KEY_KP5","KEY_KP6","KEY_KP7","KEY_KP8","KEY_KP9","KEY_KPDOT","KEY_KPENTER","KEY_KPPLUS","KEY_KPMINUS","KEY_KPASTERISK","KEY_KPSLASH"));
        g.put("builtin.mouse", List.of("BTN_LEFT","BTN_RIGHT","BTN_MIDDLE","BTN_SIDE","BTN_EXTRA"));
        return Map.copyOf(g);
    }
    private static Map<String, String> names() {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("builtin.letters", "Буквы"); result.put("builtin.digits", "Цифры"); result.put("builtin.function", "F1–F12");
        result.put("builtin.modifiers", "Модификаторы"); result.put("builtin.navigation", "Навигация");
        result.put("builtin.numpad", "NumPad"); result.put("builtin.mouse", "Кнопки мыши"); return Map.copyOf(result);
    }
    private static List<String> range(String prefix, String last, int firstCode) {
        List<String> out = new ArrayList<>(); int end = last.charAt(last.length()-1) - 'A' + 1;
        for (int i=0;i<end;i++) out.add(prefix + (char)('A'+i)); return List.copyOf(out);
    }
    public static Map<String, Object> map(Object... pairs) { Map<String,Object> result=new LinkedHashMap<>(); for(int i=0;i<pairs.length;i+=2) result.put((String)pairs[i],pairs[i+1]); return result; }
    public record SoundAsset(String id, String name, List<String> tags) {}
}
