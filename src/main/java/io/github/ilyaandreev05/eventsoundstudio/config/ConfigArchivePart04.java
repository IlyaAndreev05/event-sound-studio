package io.github.ilyaandreev05.eventsoundstudio.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.github.ilyaandreev05.eventsoundstudio.audio.MixerOutputSelector;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;
import static io.github.ilyaandreev05.eventsoundstudio.config.ConfigArchive.*;

abstract class ConfigArchivePart04 extends ConfigArchivePart03 {
    static ProfileMerge mergeProfile(Map<String, Object> destination, Map<String, Object> source) throws IOException {
        List<Map<String, Object>> sourceProfiles = ConfigArchive.maps(source.get("profiles"));
        if (sourceProfiles.size() != 1) throw new ArchiveException("A profile archive must contain exactly one profile");

        Map<String, Object> merged = ConfigArchive.deepCopy(destination);
        List<Map<String, Object>> destinationSounds = ConfigArchive.maps(merged.get("sounds"));
        List<Map<String, Object>> destinationEvents = ConfigArchive.maps(merged.get("event_types"));
        List<Map<String, Object>> destinationGroups = ConfigArchive.maps(merged.get("groups"));
        List<Map<String, Object>> destinationBindings = ConfigArchive.maps(merged.get("bindings"));
        List<Map<String, Object>> destinationCombos = ConfigArchive.maps(merged.get("combos"));
        List<Map<String, Object>> destinationProfiles = ConfigArchive.maps(merged.get("profiles"));

        List<Map<String, Object>> sourceSounds = ConfigArchive.maps(source.get("sounds"));
        List<Map<String, Object>> sourceEvents = ConfigArchive.maps(source.get("event_types"));
        List<Map<String, Object>> sourceGroups = ConfigArchive.maps(source.get("groups"));
        List<Map<String, Object>> sourceBindings = ConfigArchive.maps(source.get("bindings"));
        List<Map<String, Object>> sourceCombos = ConfigArchive.maps(source.get("combos"));

        Set<String> requiredSoundIds = new LinkedHashSet<>();
        for (Map<String, Object> binding : sourceBindings) requiredSoundIds.add(String.valueOf(binding.get("sound")));
        for (Map<String, Object> group : sourceGroups) {
            String id = String.valueOf(group.get("id"));
            if (!DefaultConfig.BUILTIN_GROUPS.containsKey(id) && !Boolean.TRUE.equals(group.get("protected")))
                requiredSoundIds.add(String.valueOf(group.get("default_sound")));
        }
        Map<String, Map<String, Object>> sourceSoundsById = new LinkedHashMap<>();
        for (Map<String, Object> sound : sourceSounds) sourceSoundsById.put(String.valueOf(sound.get("id")), sound);
        boolean addedSoundDependency;
        do {
            addedSoundDependency = false;
            for (String id : List.copyOf(requiredSoundIds)) {
                Map<String, Object> sound = sourceSoundsById.get(id);
                if (sound == null) continue;
                for (String field : List.of("copy_of", "fragment_of")) {
                    Object dependency = sound.get(field);
                    if (dependency != null && sourceSoundsById.containsKey(String.valueOf(dependency))
                            && requiredSoundIds.add(String.valueOf(dependency))) addedSoundDependency = true;
                }
            }
        } while (addedSoundDependency);
        List<Map<String, Object>> soundsToImport = sourceSounds.stream()
                .filter(sound -> requiredSoundIds.contains(String.valueOf(sound.get("id")))).toList();
        Map<String, String> soundIds = ConfigArchive.allocateIds(soundsToImport, ConfigArchive.ids(destinationSounds));
        Map<String, String> eventIds = ConfigArchive.allocateIds(sourceEvents, ConfigArchive.ids(destinationEvents));
        Map<String, String> bindingIds = ConfigArchive.allocateIds(sourceBindings, ConfigArchive.ids(destinationBindings));
        Map<String, String> comboIds = ConfigArchive.allocateIds(sourceCombos, ConfigArchive.ids(destinationCombos));
        Map<String, String> profileIds = ConfigArchive.allocateIds(sourceProfiles, ConfigArchive.ids(destinationProfiles));
        Map<String, String> groupIds = new LinkedHashMap<>();
        Set<String> destinationGroupIds = ConfigArchive.ids(destinationGroups);
        for (Map<String, Object> group : sourceGroups) {
            String id = String.valueOf(group.get("id"));
            if (DefaultConfig.BUILTIN_GROUPS.containsKey(id)) {
                if (!destinationGroupIds.contains(id)) throw new ArchiveException("Destination is missing protected group " + id);
                groupIds.put(id, id);
            } else {
                if (Boolean.TRUE.equals(group.get("protected")))
                    throw new ArchiveException("Profile archive contains an unsupported protected group " + id);
                groupIds.put(id, ConfigArchive.allocateId(id, destinationGroupIds));
            }
        }

        List<Map<String, Object>> importedSounds = ConfigArchive.remapRecords(soundsToImport, soundIds);
        for (Map<String, Object> sound : importedSounds) {
            sound.put("builtin", false);
            ConfigArchive.remapReference(sound, "copy_of", soundIds, ConfigArchive.ids(destinationSounds));
            ConfigArchive.remapReference(sound, "fragment_of", soundIds, ConfigArchive.ids(destinationSounds));
        }

        List<Map<String, Object>> importedEvents = ConfigArchive.remapRecords(sourceEvents, eventIds);
        List<Map<String, Object>> importedCombos = ConfigArchive.remapRecords(sourceCombos, comboIds);
        for (Map<String, Object> combo : importedCombos) ConfigArchive.remapReference(combo, "event", eventIds, ConfigArchive.ids(destinationEvents));

        List<Map<String, Object>> importedGroups = new ArrayList<>();
        for (Map<String, Object> sourceGroup : sourceGroups) {
            String originalId = String.valueOf(sourceGroup.get("id"));
            if (DefaultConfig.BUILTIN_GROUPS.containsKey(originalId)) continue;
            Map<String, Object> group = ConfigArchive.deepCopy(sourceGroup);
            group.put("id", groupIds.get(originalId));
            ConfigArchive.remapReference(group, "default_sound", soundIds, Set.of());
            importedGroups.add(group);
        }

        List<Map<String, Object>> importedBindings = ConfigArchive.remapRecords(sourceBindings, bindingIds);
        Set<String> allGroupIds = new LinkedHashSet<>(destinationGroupIds);
        allGroupIds.addAll(groupIds.values());
        for (Map<String, Object> binding : importedBindings) {
            ConfigArchive.remapReference(binding, "sound", soundIds, ConfigArchive.ids(destinationSounds));
            Object eventValue = binding.get("event");
            if (eventValue instanceof String event) {
                if (event.startsWith("group:")) {
                    String groupId = event.substring("group:".length());
                    String remappedGroup = groupIds.getOrDefault(groupId, destinationGroupIds.contains(groupId) ? groupId : null);
                    if (remappedGroup == null || !allGroupIds.contains(remappedGroup))
                        throw new ArchiveException("Profile binding references a missing group " + groupId);
                    binding.put("event", "group:" + remappedGroup);
                } else if (eventIds.containsKey(event)) binding.put("event", eventIds.get(event));
                else if (!event.startsWith("key:") && !event.startsWith("button:") && !event.startsWith("wheel:")
                        && !ConfigArchive.ids(destinationEvents).contains(event))
                    throw new ArchiveException("Profile binding references a missing event " + event);
            }
            binding.put("output", "profile_default");
        }

        Map<String, Object> importedProfile = ConfigArchive.deepCopy(sourceProfiles.getFirst());
        importedProfile.put("id", profileIds.get(String.valueOf(importedProfile.get("id"))));
        importedProfile.put("output", MixerOutputSelector.AUDIO_MATRIX_EVENTS);
        List<String> enabledBindings = new ArrayList<>();
        Object enabledValue = importedProfile.get("enabled_bindings");
        if (!(enabledValue instanceof List<?> enabled)) throw new ArchiveException("Imported profile has no enabled_bindings list");
        for (Object id : enabled) {
            String mapped = bindingIds.get(String.valueOf(id));
            if (mapped == null) throw new ArchiveException("Imported profile references a missing binding " + id);
            enabledBindings.add(mapped);
        }
        importedProfile.put("enabled_bindings", enabledBindings);

        destinationSounds.addAll(importedSounds);
        destinationEvents.addAll(importedEvents);
        destinationGroups.addAll(importedGroups);
        destinationBindings.addAll(importedBindings);
        destinationCombos.addAll(importedCombos);
        destinationProfiles.add(importedProfile);

        List<String> errors = ConfigValidator.validate(merged);
        if (!errors.isEmpty()) throw new ArchiveException("Could not merge profile: " + String.join("; ", errors));
        Set<String> installedSoundIds = new LinkedHashSet<>(soundIds.values());
        return new ProfileMerge(merged, importedSounds, installedSoundIds);
    }

    static Map<String, String> selectedAssetHashes(Map<String, Object> importedConfig, Set<String> soundIds,
                                                            Map<String, String> archiveHashes) throws IOException {
        TreeMap<String, String> selected = new TreeMap<>();
        for (Map<String, Object> sound : ConfigArchive.maps(importedConfig.get("sounds"))) {
            if (!soundIds.contains(String.valueOf(sound.get("id")))) continue;
            for (String field : List.of("path", "original_path")) {
                Object value = sound.get(field);
                if (!(value instanceof String path)) continue;
                String archiveName = "data/" + ConfigArchive.safeRelativePath(path).toString().replace('\\', '/');
                String hash = archiveHashes.get(archiveName);
                if (hash == null) throw new ArchiveException("Archive is missing sound resource " + path);
                selected.put(archiveName, hash);
            }
        }
        return selected;
    }

    static Map<String, Object> mapOfSounds(List<Map<String, Object>> sounds) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("sounds", sounds);
        return config;
    }
}
