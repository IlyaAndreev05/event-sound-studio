package io.github.ilyaandreev05.eventsoundstudio.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Schema-five defaults are installed once, never on every config load or in partial archives. */
final class EventPresetMigration {
    private EventPresetMigration() {}

    static void apply(Map<String,Object> root) {
        Map<String,Object> defaults = DefaultConfig.create();
        for (String key : List.of("sounds", "event_types", "groups")) mergeMissing(root, defaults, key);
        List<Map<String,Object>> bindings = mutableList(root, "bindings");
        List<String> added = new ArrayList<>();
        for (Map<String,Object> binding : maps(defaults.get("bindings"))) {
            String id = String.valueOf(binding.get("id"));
            if (bindings.stream().noneMatch(existing -> id.equals(existing.get("id")) || binding.get("event").equals(existing.get("event")))) {
                bindings.add(new LinkedHashMap<>(binding));
                if (Boolean.TRUE.equals(binding.get("enabled"))) added.add(id);
            }
        }
        for (Map<String,Object> binding : bindings) {
            String id = String.valueOf(binding.get("id"));
            String oldSound = switch (id) {
                case "mouse-middle-click" -> "octine-click";
                case "workspace-change" -> "octine-reminder";
                case "super-key-press", "super-right-key-press" -> "octine-system";
                default -> "";
            };
            if (!oldSound.equals(binding.get("sound"))) continue;
            maps(defaults.get("bindings")).stream().filter(candidate -> id.equals(candidate.get("id"))).findFirst()
                    .ifPresent(candidate -> { binding.put("sound", candidate.get("sound")); binding.put("duration_ms", candidate.get("duration_ms")); });
        }
        for (Map<String,Object> profile : maps(root.get("profiles"))) {
            if (!"desk".equals(profile.get("id"))) continue;
            List<Object> enabled = new ArrayList<>((List<?>) profile.get("enabled_bindings"));
            for (String id : added) if (!enabled.contains(id)) enabled.add(id);
            profile.put("enabled_bindings", enabled);
        }
    }

    private static void mergeMissing(Map<String,Object> root, Map<String,Object> defaults, String key) {
        List<Map<String,Object>> existing = mutableList(root, key);
        for (Map<String,Object> item : maps(defaults.get(key)))
            if (existing.stream().noneMatch(candidate -> item.get("id").equals(candidate.get("id"))))
                existing.add(new LinkedHashMap<>(item));
    }

    private static List<Map<String,Object>> mutableList(Map<String,Object> root, String key) {
        List<Map<String,Object>> list = new ArrayList<>(maps(root.get(key)));
        root.put(key, list);
        return list;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String,Object>> maps(Object value) { return value instanceof List<?> list ? (List<Map<String,Object>>) list : List.of(); }
}
