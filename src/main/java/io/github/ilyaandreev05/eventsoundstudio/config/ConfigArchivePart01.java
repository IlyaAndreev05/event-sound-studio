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

abstract class ConfigArchivePart01 extends ConfigArchiveCore {
    public static void exportArchive(Path destination, Map<String, Object> config, Path dataDirectory) throws IOException {
        ConfigArchive.writeArchive(destination, config, dataDirectory, "full");
    }

    static void writeArchive(Path destination, Map<String, Object> config, Path dataDirectory,
                                     String kind) throws IOException {
        List<String> errors = "sound-set".equals(kind) ? ConfigArchive.validateSoundSetConfig(config) : ConfigValidator.validate(config);
        if (!errors.isEmpty()) throw new ArchiveException("Configuration is invalid: " + String.join("; ", errors));
        Path root = dataDirectory.toAbsolutePath().normalize();
        byte[] configBytes = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(config);
        TreeMap<String, Path> assets = ConfigArchive.referencedAssets(config, root);
        TreeMap<String, String> hashes = new TreeMap<>();
        hashes.put("config.json", ConfigArchive.sha256(configBytes));
        for (var asset : assets.entrySet()) hashes.put("data/" + asset.getKey(), ConfigArchive.sha256(asset.getValue()));
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("format", ArchiveManifestMigrator.FORMAT);
        manifest.put("version", ArchiveManifestMigrator.CURRENT_VERSION);
        manifest.put("app", ArchiveManifestMigrator.APP);
        manifest.put("config_schema_version", ConfigArchive.number(config.get("schema_version"), -1));
        manifest.put("config_entry","config.json");
        manifest.put("kind", kind);
        manifest.put("files", hashes);
        byte[] manifestBytes = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest);

        Path target = destination.toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent == null) throw new ArchiveException("Archive destination has no parent directory");
        Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, ".ess-archive-", ".zip.tmp");
        try {
            try (OutputStream file = Files.newOutputStream(temp); ZipOutputStream zip = new ZipOutputStream(file)) {
                ConfigArchive.put(zip, "manifest.json", manifestBytes);
                ConfigArchive.put(zip, "config.json", configBytes);
                for (var asset : assets.entrySet()) ConfigArchive.put(zip, "data/" + asset.getKey(), asset.getValue());
            }
            try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { throw new ArchiveException("Archive destination does not support atomic replacement", e); }
        } finally { Files.deleteIfExists(temp); }
    }

    public static void exportProfileArchive(Path destination, Map<String, Object> config,
                                            Path dataDirectory, String profileId) throws IOException {
        ConfigArchive.writeArchive(destination, ConfigArchive.profilePackageConfig(config, profileId), dataDirectory, "profile");
    }

    public static void exportBindingSetArchive(Path destination, Map<String, Object> config,
                                               Path dataDirectory, Set<String> selectedBindingIds) throws IOException {
        List<String> errors = ConfigValidator.validate(config);
        if (!errors.isEmpty()) throw new ArchiveException("Configuration is invalid: " + String.join("; ", errors));
        if (selectedBindingIds == null || selectedBindingIds.isEmpty()) throw new ArchiveException("Select at least one binding to export");
        Map<String, Object> portable = ConfigArchive.deepCopy(config);
        List<Map<String, Object>> profiles = ConfigArchive.maps(portable.get("profiles"));
        String activeId = String.valueOf(portable.get("active_profile"));
        Map<String, Object> active = profiles.stream().filter(profile -> activeId.equals(profile.get("id"))).findFirst()
                .orElseThrow(() -> new ArchiveException("Active profile does not exist: " + activeId));
        List<Map<String, Object>> bindings = ConfigArchive.maps(portable.get("bindings"));
        Set<String> available = ConfigArchive.ids(bindings);
        if (!available.containsAll(selectedBindingIds)) throw new ArchiveException("Selection contains a binding that no longer exists");
        List<String> orderedSelection = bindings.stream().map(binding -> String.valueOf(binding.get("id")))
                .filter(selectedBindingIds::contains).toList();
        String packageProfileId = ConfigArchive.allocateId("binding-set", ConfigArchive.ids(profiles));
        Map<String, Object> packageProfile = ConfigArchive.deepCopy(active);
        packageProfile.put("id", packageProfileId);
        packageProfile.put("name", "en".equals(ConfigArchive.objectMap(portable.get("settings")).get("language")) ? "Binding set" : "Набор привязок");
        packageProfile.put("enabled_bindings", orderedSelection);
        packageProfile.put("output", MixerOutputSelector.AUDIO_MATRIX_EVENTS);
        ConfigArchive.objectMap(portable.get("settings")).put("preview_output", "system_default");
        profiles.add(packageProfile);
        portable.put("active_profile", packageProfileId);
        ConfigArchive.exportProfileArchive(destination, portable, dataDirectory, packageProfileId);
    }

    public static void exportSoundSetArchive(Path destination, Map<String, Object> config,
                                             Path dataDirectory, Set<String> selectedSoundIds) throws IOException {
        List<String> errors = ConfigValidator.validate(config);
        if (!errors.isEmpty()) throw new ArchiveException("Configuration is invalid: " + String.join("; ", errors));
        if (selectedSoundIds == null || selectedSoundIds.isEmpty()) throw new ArchiveException("Select at least one sound to export");
        List<Map<String, Object>> allSounds = ConfigArchive.maps(config.get("sounds"));
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> sound : allSounds) byId.put(String.valueOf(sound.get("id")), sound);
        for (String id : selectedSoundIds) if (!byId.containsKey(id)) throw new ArchiveException("Sound does not exist: " + id);
        Set<String> includedIds = ConfigArchive.soundDependencyClosure(allSounds, selectedSoundIds);
        List<Map<String, Object>> selected = allSounds.stream().filter(sound -> includedIds.contains(String.valueOf(sound.get("id"))))
                .map(ConfigArchive::deepCopy).toList();
        Map<String, Object> packageConfig = new LinkedHashMap<>();
        packageConfig.put("schema_version", ConfigMigrator.CURRENT_SCHEMA_VERSION);
        packageConfig.putIfAbsent("graph_routes", new ArrayList<>());
        packageConfig.putIfAbsent("route_groups", new ArrayList<>());
        packageConfig.putIfAbsent("saved_copies", new ArrayList<>());
        packageConfig.put("app", "event-sound-studio");
        packageConfig.put("sounds", selected);
        ConfigArchive.writeArchive(destination, packageConfig, dataDirectory, "sound-set");
    }

    static List<String> validateSoundSetConfig(Map<String, Object> config) throws IOException {
        int configVersion = ConfigArchive.number(config.get("schema_version"), -1);
        if (configVersion >= 1 && configVersion < ConfigMigrator.CURRENT_SCHEMA_VERSION)
            config.put("schema_version", ConfigMigrator.CURRENT_SCHEMA_VERSION);
        if (ConfigArchive.number(config.get("schema_version"), -1) != ConfigMigrator.CURRENT_SCHEMA_VERSION || !"event-sound-studio".equals(config.get("app")))
            throw new ArchiveException("Sound-set config has an unsupported app or schema version");
        Object soundsValue = config.get("sounds");
        if (!(soundsValue instanceof List<?> sounds) || sounds.isEmpty()) throw new ArchiveException("Sound-set archive must contain at least one sound");
        List<Map<String, Object>> records = new ArrayList<>();
        Set<String> recordIds = new LinkedHashSet<>();
        for (Object item : sounds) {
            if (!(item instanceof Map<?, ?> raw)) throw new ArchiveException("Sound-set entries must be mappings");
            @SuppressWarnings("unchecked") Map<String, Object> record = (Map<String, Object>) raw;
            if (!(record.get("id") instanceof String id) || !recordIds.add(id)) throw new ArchiveException("Sound-set contains a missing or duplicate sound ID");
            records.add(record);
        }
        ConfigArchive.soundDependencyClosure(records, recordIds);
        Map<String, Object> validation = DefaultConfig.create();
        List<Map<String, Object>> combined = new ArrayList<>(ConfigArchive.maps(validation.get("sounds")));
        for (Map<String, Object> imported : records) {
            String id = String.valueOf(imported.get("id"));
            combined.removeIf(existing -> id.equals(existing.get("id")));
            combined.add(ConfigArchive.deepCopy(imported));
        }
        validation.put("sounds", combined);
        List<String> errors = ConfigValidator.validate(validation);
        if (!errors.isEmpty()) throw new ArchiveException("Sound-set entries are invalid: " + String.join("; ", errors));
        return List.of();
    }

    static Set<String> soundDependencyClosure(List<Map<String, Object>> sounds, Set<String> roots) throws IOException {
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> sound : sounds) byId.put(String.valueOf(sound.get("id")), sound);
        Set<String> included = new LinkedHashSet<>(roots);
        boolean added;
        do {
            added = false;
            for (String id : List.copyOf(included)) {
                Map<String, Object> sound = byId.get(id);
                if (sound == null) throw new ArchiveException("Sound dependency does not exist: " + id);
                for (String field : List.of("copy_of", "fragment_of")) {
                    Object reference = sound.get(field);
                    if (reference == null) continue;
                    String dependency = String.valueOf(reference);
                    if (!byId.containsKey(dependency)) throw new ArchiveException("Sound " + id + " references missing " + field + " sound " + dependency);
                    if (included.add(dependency)) added = true;
                }
            }
        } while (added);
        return included;
    }
}
