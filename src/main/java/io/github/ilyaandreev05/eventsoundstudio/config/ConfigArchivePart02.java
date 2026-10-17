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

abstract class ConfigArchivePart02 extends ConfigArchivePart01 {
    static Map<String, Object> profilePackageConfig(Map<String, Object> source, String profileId) throws IOException {
        List<String> sourceErrors = ConfigValidator.validate(source);
        if (!sourceErrors.isEmpty()) throw new ArchiveException("Configuration is invalid: " + String.join("; ", sourceErrors));
        Map<String, Object> portable = ConfigArchive.deepCopy(source);
        List<Map<String, Object>> profiles = ConfigArchive.maps(portable.get("profiles"));
        Map<String, Object> profile = profiles.stream().filter(item -> profileId.equals(item.get("id"))).findFirst()
                .orElseThrow(() -> new ArchiveException("Profile does not exist: " + profileId));
        Set<String> selectedBindingIds = new LinkedHashSet<>();
        for (Object rawId : (List<?>) profile.get("enabled_bindings")) selectedBindingIds.add(String.valueOf(rawId));
        List<Map<String, Object>> bindings = ConfigArchive.maps(portable.get("bindings")).stream()
                .filter(binding -> selectedBindingIds.contains(binding.get("id"))).toList();
        Set<String> eventIds = new LinkedHashSet<>(), groupIds = new LinkedHashSet<>(), soundIds = new LinkedHashSet<>();
        for (Map<String, Object> binding : bindings) {
            String event = String.valueOf(binding.get("event"));
            if (event.startsWith("group:")) groupIds.add(event.substring("group:".length()));
            else if (!event.startsWith("key:") && !event.startsWith("button:") && !event.startsWith("wheel:")) eventIds.add(event);
            soundIds.add(String.valueOf(binding.get("sound")));
            binding.put("output", "profile_default");
        }
        List<Map<String, Object>> groups = ConfigArchive.maps(portable.get("groups"));
        groups.stream().filter(group -> Boolean.TRUE.equals(group.get("protected"))
                || groupIds.contains(String.valueOf(group.get("id")))).forEach(group -> {
            Object sound = group.get("default_sound");
            if (sound != null) soundIds.add(String.valueOf(sound));
        });
        List<Map<String, Object>> allSounds = ConfigArchive.maps(portable.get("sounds"));
        soundIds.addAll(ConfigArchive.soundDependencyClosure(allSounds, soundIds));

        profile.put("output", MixerOutputSelector.AUDIO_MATRIX_EVENTS);
        profile.put("enabled_bindings", new ArrayList<>(selectedBindingIds));
        portable.put("profiles", List.of(profile));
        portable.put("active_profile", profileId);
        portable.put("bindings", bindings);
        portable.put("event_types", ConfigArchive.maps(portable.get("event_types")).stream()
                .filter(event -> eventIds.contains(event.get("id"))).toList());
        portable.put("combos", ConfigArchive.maps(portable.get("combos")).stream()
                .filter(combo -> eventIds.contains(combo.get("event"))).toList());
        portable.put("groups", groups.stream().filter(group -> Boolean.TRUE.equals(group.get("protected"))
                || groupIds.contains(group.get("id"))).toList());
        portable.put("sounds", ConfigArchive.maps(portable.get("sounds")).stream()
                .filter(sound -> soundIds.contains(sound.get("id"))).toList());
        portable.remove("source_profiles");
        Map<String, Object> settings = ConfigArchive.objectMap(portable.get("settings"));
        Map<String, Object> input = ConfigArchive.objectMap(settings.get("input"));
        input.put("enable_keyboard", false);
        input.put("enable_mouse", false);
        input.put("enable_gnome", false);
        input.put("device_allowlist", new ArrayList<>());
        input.put("log_key_sequences", false);
        settings.put("input", input);
        settings.put("preview_output", "system_default");
        portable.put("settings", settings);
        Map<String, Object> api = ConfigArchive.objectMap(portable.get("api"));
        api.put("enabled", false);
        portable.put("api", api);
        List<String> errors = ConfigValidator.validate(portable);
        if (!errors.isEmpty()) throw new ArchiveException("Profile package is invalid: " + String.join("; ", errors));
        return portable;
    }

    public static PreparedImport prepare(Path archive) throws IOException {
        Path staging = Files.createTempDirectory("ess-restore-");
        try {
            int entries = 0;
            long total = 0;
            Map<String, Path> extracted = new LinkedHashMap<>();
            try (InputStream file = Files.newInputStream(archive); ZipInputStream zip = new ZipInputStream(file)) {
                ZipEntry entry;
                byte[] buffer = new byte[16 * 1024];
                while ((entry = zip.getNextEntry()) != null) {
                    if (++entries > MAX_ENTRIES) throw new ArchiveException("Archive has too many entries");
                    if (entry.isDirectory()) { zip.closeEntry(); continue; }
                    String name = entry.getName();
                    ConfigArchive.validateArchivePath(name);
                    if (extracted.containsKey(name)) throw new ArchiveException("Archive contains a duplicate path: " + name);
                    Path output = staging.resolve(name).normalize();
                    if (!output.startsWith(staging)) throw new ArchiveException("Archive path escapes the staging directory");
                    Files.createDirectories(output.getParent());
                    long itemSize = 0;
                    try (OutputStream out = Files.newOutputStream(output)) {
                        int count;
                        while ((count = zip.read(buffer)) >= 0) {
                            if (count == 0) continue;
                            itemSize += count;
                            total += count;
                            if (total > MAX_UNPACKED_BYTES) throw new ArchiveException("Archive expands beyond the 512 MiB safety limit");
                            out.write(buffer, 0, count);
                        }
                    }
                    if (itemSize == 0 && !name.equals("config.yaml") && !name.equals("config.json")) throw new ArchiveException("Archive contains an empty resource: " + name);
                    extracted.put(name, output);
                    zip.closeEntry();
                }
            }
            Path manifestPath = extracted.get("manifest.json");
            if (manifestPath == null) throw new ArchiveException("Archive must contain manifest.json");
            Map<String, Object> manifest = JSON.readValue(Files.readAllBytes(manifestPath), MAP_TYPE);
            int archiveVersion=ConfigArchive.number(manifest.get("version"),-1);
            String legacyDefault=extracted.containsKey("config.json")?"config.json":"config.yaml";
            String configEntry=String.valueOf(manifest.getOrDefault("config_entry",archiveVersion>=3?"config.json":legacyDefault));
            Path configPath=List.of("config.json","config.yaml").contains(configEntry)?extracted.get(configEntry):null;
            if(configPath==null)throw new ArchiveException("Archive is missing its declared config document");
            Object filesValue = manifest.get("files");
            if (!(filesValue instanceof Map<?, ?> files)) throw new ArchiveException("Archive manifest has no file hash table");
            TreeMap<String, String> expected = new TreeMap<>();
            for (var file : files.entrySet()) {
                if (!(file.getKey() instanceof String name) || !(file.getValue() instanceof String hash) || !hash.matches("[0-9a-f]{64}"))
                    throw new ArchiveException("Archive manifest contains an invalid file record");
                ConfigArchive.validateArchivePath(name);
                expected.put(name, hash);
            }
            java.util.Set<String> extractedFiles = new java.util.TreeSet<>(extracted.keySet());
            extractedFiles.remove("manifest.json");
            if (!expected.keySet().equals(extractedFiles))
                throw new ArchiveException("Archive files do not match the manifest");
            for (var file : expected.entrySet()) {
                if (!file.getValue().equals(ConfigArchive.sha256(extracted.get(file.getKey())))) throw new ArchiveException("Archive resource checksum failed: " + file.getKey());
            }

            Map<String, Object> config = "config.json".equals(configEntry)?JSON.readValue(Files.readAllBytes(configPath), MAP_TYPE):YAML.readValue(Files.readAllBytes(configPath), MAP_TYPE);
            if (config == null) throw new ArchiveException("Archive config must be a JSON or YAML mapping");
            int sourceSchema=ConfigArchive.number(config.get("schema_version"),-1);
            manifest = ArchiveManifestMigrator.migrate(manifest, sourceSchema);
            if(sourceSchema<3){config.put("schema_version",3);config.putIfAbsent("graph_routes",new ArrayList<>());config.putIfAbsent("route_groups",new ArrayList<>());config.putIfAbsent("saved_copies",new ArrayList<>());ConfigMigrator.migrateKnownDefaults(config,false);}
            if(sourceSchema<ConfigMigrator.CURRENT_SCHEMA_VERSION){
                ConfigMigrator.migrateToCurrentSchema(config,false);
                manifest.put("config_schema_version",ConfigMigrator.CURRENT_SCHEMA_VERSION);
            }
            Object kindValue = manifest.get("kind");
            if (!(kindValue instanceof String kind) || !ArchiveManifestMigrator.validKind(kind))
                throw new ArchiveException("Archive manifest has an unsupported kind");
            if ("sound-set".equals(kind)) ConfigArchive.validateSoundSetConfig(config);
            else {
                List<String> errors = ConfigValidator.validate(config);
                if (!errors.isEmpty()) throw new ArchiveException("Imported configuration is invalid: " + String.join("; ", errors));
            }
            ConfigArchive.verifySoundReferences(config, expected);
            return new PreparedImport(staging, ConfigArchive.deepCopy(config), expected, kind,
                    ConfigArchive.count(config, "sounds"), ConfigArchive.count(config, "profiles"), ConfigArchive.count(config, "bindings"));
        } catch (Exception e) {
            ConfigArchive.deleteTree(staging);
            if (e instanceof IOException io) throw io;
            throw new ArchiveException("Could not prepare archive: " + e.getMessage(), e);
        }
    }
}
