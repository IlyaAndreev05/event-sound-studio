package io.github.ilyaandreev05.eventsoundstudio.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.LinkedHashSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Portable, validated backup of an Event Sound Studio configuration and its WAV resources. */
public final class ConfigArchive {
    private static final int MAX_ENTRIES = 512;
    private static final long MAX_UNPACKED_BYTES = 512L * 1024 * 1024;
    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private ConfigArchive() {}

    public static void exportArchive(Path destination, Map<String, Object> config, Path dataDirectory) throws IOException {
        writeArchive(destination, config, dataDirectory, "full");
    }

    private static void writeArchive(Path destination, Map<String, Object> config, Path dataDirectory,
                                     String kind) throws IOException {
        List<String> errors = "sound-set".equals(kind) ? validateSoundSetConfig(config) : ConfigValidator.validate(config);
        if (!errors.isEmpty()) throw new ArchiveException("Configuration is invalid: " + String.join("; ", errors));
        Path root = dataDirectory.toAbsolutePath().normalize();
        byte[] configBytes = YAML.writeValueAsBytes(config);
        TreeMap<String, Path> assets = referencedAssets(config, root);
        TreeMap<String, String> hashes = new TreeMap<>();
        hashes.put("config.yaml", sha256(configBytes));
        for (var asset : assets.entrySet()) hashes.put("data/" + asset.getKey(), sha256(asset.getValue()));
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("format", ArchiveManifestMigrator.FORMAT);
        manifest.put("version", ArchiveManifestMigrator.CURRENT_VERSION);
        manifest.put("app", ArchiveManifestMigrator.APP);
        manifest.put("config_schema_version", number(config.get("schema_version"), -1));
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
                put(zip, "manifest.json", manifestBytes);
                put(zip, "config.yaml", configBytes);
                for (var asset : assets.entrySet()) put(zip, "data/" + asset.getKey(), asset.getValue());
            }
            try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { throw new ArchiveException("Archive destination does not support atomic replacement", e); }
        } finally { Files.deleteIfExists(temp); }
    }

    /** Exports one self-contained profile and its binding/event/group/sound dependencies. */
    public static void exportProfileArchive(Path destination, Map<String, Object> config,
                                            Path dataDirectory, String profileId) throws IOException {
        writeArchive(destination, profilePackageConfig(config, profileId), dataDirectory, "profile");
    }

    /** Packages selected bindings as a portable one-profile archive for later merge import. */
    public static void exportBindingSetArchive(Path destination, Map<String, Object> config,
                                               Path dataDirectory, Set<String> selectedBindingIds) throws IOException {
        List<String> errors = ConfigValidator.validate(config);
        if (!errors.isEmpty()) throw new ArchiveException("Configuration is invalid: " + String.join("; ", errors));
        if (selectedBindingIds == null || selectedBindingIds.isEmpty()) throw new ArchiveException("Select at least one binding to export");
        Map<String, Object> portable = deepCopy(config);
        List<Map<String, Object>> profiles = maps(portable.get("profiles"));
        String activeId = String.valueOf(portable.get("active_profile"));
        Map<String, Object> active = profiles.stream().filter(profile -> activeId.equals(profile.get("id"))).findFirst()
                .orElseThrow(() -> new ArchiveException("Active profile does not exist: " + activeId));
        List<Map<String, Object>> bindings = maps(portable.get("bindings"));
        Set<String> available = ids(bindings);
        if (!available.containsAll(selectedBindingIds)) throw new ArchiveException("Selection contains a binding that no longer exists");
        List<String> orderedSelection = bindings.stream().map(binding -> String.valueOf(binding.get("id")))
                .filter(selectedBindingIds::contains).toList();
        String packageProfileId = allocateId("binding-set", ids(profiles));
        Map<String, Object> packageProfile = deepCopy(active);
        packageProfile.put("id", packageProfileId);
        packageProfile.put("name", "en".equals(objectMap(portable.get("settings")).get("language")) ? "Binding set" : "Набор привязок");
        packageProfile.put("enabled_bindings", orderedSelection);
        packageProfile.put("output", "system_default");
        profiles.add(packageProfile);
        portable.put("active_profile", packageProfileId);
        exportProfileArchive(destination, portable, dataDirectory, packageProfileId);
    }

    /** Exports selected sounds and their copy/fragment provenance as a portable sound-set ZIP. */
    public static void exportSoundSetArchive(Path destination, Map<String, Object> config,
                                             Path dataDirectory, Set<String> selectedSoundIds) throws IOException {
        List<String> errors = ConfigValidator.validate(config);
        if (!errors.isEmpty()) throw new ArchiveException("Configuration is invalid: " + String.join("; ", errors));
        if (selectedSoundIds == null || selectedSoundIds.isEmpty()) throw new ArchiveException("Select at least one sound to export");
        List<Map<String, Object>> allSounds = maps(config.get("sounds"));
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> sound : allSounds) byId.put(String.valueOf(sound.get("id")), sound);
        for (String id : selectedSoundIds) if (!byId.containsKey(id)) throw new ArchiveException("Sound does not exist: " + id);
        Set<String> includedIds = soundDependencyClosure(allSounds, selectedSoundIds);
        List<Map<String, Object>> selected = allSounds.stream().filter(sound -> includedIds.contains(String.valueOf(sound.get("id"))))
                .map(ConfigArchive::deepCopy).toList();
        Map<String, Object> packageConfig = new LinkedHashMap<>();
        packageConfig.put("schema_version", 1);
        packageConfig.put("app", "event-sound-studio");
        packageConfig.put("sounds", selected);
        writeArchive(destination, packageConfig, dataDirectory, "sound-set");
    }

    private static List<String> validateSoundSetConfig(Map<String, Object> config) throws IOException {
        if (number(config.get("schema_version"), -1) != 1 || !"event-sound-studio".equals(config.get("app")))
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
        soundDependencyClosure(records, recordIds);
        Map<String, Object> validation = DefaultConfig.create();
        List<Map<String, Object>> combined = new ArrayList<>(maps(validation.get("sounds")));
        for (Map<String, Object> imported : records) {
            String id = String.valueOf(imported.get("id"));
            combined.removeIf(existing -> id.equals(existing.get("id")));
            combined.add(deepCopy(imported));
        }
        validation.put("sounds", combined);
        List<String> errors = ConfigValidator.validate(validation);
        if (!errors.isEmpty()) throw new ArchiveException("Sound-set entries are invalid: " + String.join("; ", errors));
        return List.of();
    }

    private static Set<String> soundDependencyClosure(List<Map<String, Object>> sounds, Set<String> roots) throws IOException {
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

    static Map<String, Object> profilePackageConfig(Map<String, Object> source, String profileId) throws IOException {
        List<String> sourceErrors = ConfigValidator.validate(source);
        if (!sourceErrors.isEmpty()) throw new ArchiveException("Configuration is invalid: " + String.join("; ", sourceErrors));
        Map<String, Object> portable = deepCopy(source);
        List<Map<String, Object>> profiles = maps(portable.get("profiles"));
        Map<String, Object> profile = profiles.stream().filter(item -> profileId.equals(item.get("id"))).findFirst()
                .orElseThrow(() -> new ArchiveException("Profile does not exist: " + profileId));
        Set<String> selectedBindingIds = new LinkedHashSet<>();
        for (Object rawId : (List<?>) profile.get("enabled_bindings")) selectedBindingIds.add(String.valueOf(rawId));
        List<Map<String, Object>> bindings = maps(portable.get("bindings")).stream()
                .filter(binding -> selectedBindingIds.contains(binding.get("id"))).toList();
        Set<String> eventIds = new LinkedHashSet<>(), groupIds = new LinkedHashSet<>(), soundIds = new LinkedHashSet<>();
        for (Map<String, Object> binding : bindings) {
            String event = String.valueOf(binding.get("event"));
            if (event.startsWith("group:")) groupIds.add(event.substring("group:".length()));
            else if (!event.startsWith("key:") && !event.startsWith("button:") && !event.startsWith("wheel:")) eventIds.add(event);
            soundIds.add(String.valueOf(binding.get("sound")));
            binding.put("output", "profile_default");
        }
        List<Map<String, Object>> groups = maps(portable.get("groups"));
        groups.stream().filter(group -> Boolean.TRUE.equals(group.get("protected"))
                || groupIds.contains(String.valueOf(group.get("id")))).forEach(group -> {
            Object sound = group.get("default_sound");
            if (sound != null) soundIds.add(String.valueOf(sound));
        });
        List<Map<String, Object>> allSounds = maps(portable.get("sounds"));
        soundIds.addAll(soundDependencyClosure(allSounds, soundIds));

        profile.put("output", "system_default");
        profile.put("enabled_bindings", new ArrayList<>(selectedBindingIds));
        portable.put("profiles", List.of(profile));
        portable.put("active_profile", profileId);
        portable.put("bindings", bindings);
        portable.put("event_types", maps(portable.get("event_types")).stream()
                .filter(event -> eventIds.contains(event.get("id"))).toList());
        portable.put("combos", maps(portable.get("combos")).stream()
                .filter(combo -> eventIds.contains(combo.get("event"))).toList());
        portable.put("groups", groups.stream().filter(group -> Boolean.TRUE.equals(group.get("protected"))
                || groupIds.contains(group.get("id"))).toList());
        portable.put("sounds", maps(portable.get("sounds")).stream()
                .filter(sound -> soundIds.contains(sound.get("id"))).toList());
        portable.remove("source_profiles");
        Map<String, Object> settings = objectMap(portable.get("settings"));
        Map<String, Object> input = objectMap(settings.get("input"));
        input.put("enable_keyboard", false);
        input.put("enable_mouse", false);
        input.put("enable_gnome", false);
        input.put("device_allowlist", new ArrayList<>());
        input.put("log_key_sequences", false);
        settings.put("input", input);
        portable.put("settings", settings);
        Map<String, Object> api = objectMap(portable.get("api"));
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
                    validateArchivePath(name);
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
                    if (itemSize == 0 && !name.equals("config.yaml")) throw new ArchiveException("Archive contains an empty resource: " + name);
                    extracted.put(name, output);
                    zip.closeEntry();
                }
            }
            Path manifestPath = extracted.get("manifest.json");
            Path configPath = extracted.get("config.yaml");
            if (manifestPath == null || configPath == null) throw new ArchiveException("Archive must contain manifest.json and config.yaml");
            Map<String, Object> manifest = JSON.readValue(Files.readAllBytes(manifestPath), MAP_TYPE);
            Object filesValue = manifest.get("files");
            if (!(filesValue instanceof Map<?, ?> files)) throw new ArchiveException("Archive manifest has no file hash table");
            TreeMap<String, String> expected = new TreeMap<>();
            for (var file : files.entrySet()) {
                if (!(file.getKey() instanceof String name) || !(file.getValue() instanceof String hash) || !hash.matches("[0-9a-f]{64}"))
                    throw new ArchiveException("Archive manifest contains an invalid file record");
                validateArchivePath(name);
                expected.put(name, hash);
            }
            java.util.Set<String> extractedFiles = new java.util.TreeSet<>(extracted.keySet());
            extractedFiles.remove("manifest.json");
            if (!expected.keySet().equals(extractedFiles))
                throw new ArchiveException("Archive files do not match the manifest");
            for (var file : expected.entrySet()) {
                if (!file.getValue().equals(sha256(extracted.get(file.getKey())))) throw new ArchiveException("Archive resource checksum failed: " + file.getKey());
            }

            Map<String, Object> config = YAML.readValue(Files.readAllBytes(configPath), MAP_TYPE);
            if (config == null) throw new ArchiveException("Archive config must be a YAML mapping");
            manifest = ArchiveManifestMigrator.migrate(manifest, number(config.get("schema_version"), -1));
            Object kindValue = manifest.get("kind");
            if (!(kindValue instanceof String kind) || !ArchiveManifestMigrator.validKind(kind))
                throw new ArchiveException("Archive manifest has an unsupported kind");
            if ("sound-set".equals(kind)) validateSoundSetConfig(config);
            else {
                ConfigMigrator.migrateKnownDefaults(config);
                List<String> errors = ConfigValidator.validate(config);
                if (!errors.isEmpty()) throw new ArchiveException("Imported configuration is invalid: " + String.join("; ", errors));
            }
            verifySoundReferences(config, expected);
            return new PreparedImport(staging, deepCopy(config), expected, kind,
                    count(config, "sounds"), count(config, "profiles"), count(config, "bindings"));
        } catch (Exception e) {
            deleteTree(staging);
            if (e instanceof IOException io) throw io;
            throw new ArchiveException("Could not prepare archive: " + e.getMessage(), e);
        }
    }

    public static void install(PreparedImport prepared, Path dataDirectory, ConfigStore store) throws IOException, ConfigStore.ConfigValidationException, ConfigStore.ConfigConflictException {
        if (prepared.closed) throw new ArchiveException("Prepared import has already been closed");
        Path root = dataDirectory.toAbsolutePath().normalize();
        ConfigStore.VersionedSnapshot baseSnapshot=store.versionedSnapshot();
        Map<String, Object> importConfig;
        Map<String, Object> installedConfig;
        Set<String> importSoundIds = null;
        if (prepared.profileArchive()) {
            ProfileMerge merge = mergeProfile(baseSnapshot.config(), prepared.config);
            importConfig = mapOfSounds(merge.sounds());
            installedConfig = merge.config();
            importSoundIds = merge.soundIds();
        } else if (prepared.soundSetArchive()) {
            SoundSetMerge merge = mergeSoundSet(baseSnapshot.config(), prepared.config);
            importConfig = mapOfSounds(merge.sounds());
            installedConfig = merge.config();
            importSoundIds = merge.soundIds();
        } else {
            importConfig = deepCopy(prepared.config);
            installedConfig = importConfig;
        }

        Map<String, String> hashesToInstall = prepared.profileArchive() || prepared.soundSetArchive()
                ? selectedAssetHashes(importConfig, importSoundIds, prepared.hashes)
                : prepared.hashes;
        Map<String, String> remapped = new LinkedHashMap<>();
        copyAssets(prepared, root, hashesToInstall, remapped);
        remapSoundPaths(importConfig, remapped);
        if (prepared.profileArchive() || prepared.soundSetArchive()) {
            List<String> errors = ConfigValidator.validate(installedConfig);
            if (!errors.isEmpty()) throw new ArchiveException("Merged import configuration is invalid: " + String.join("; ", errors));
        } else {
            disableGlobalSources(installedConfig);
        }
        store.save(installedConfig,baseSnapshot.revision());
    }

    private static void copyAssets(PreparedImport prepared, Path root, Map<String, String> hashes,
                                   Map<String, String> remapped) throws IOException {
        for (String archiveName : hashes.keySet()) {
            if (!archiveName.startsWith("data/")) continue;
            String relative = archiveName.substring("data/".length());
            Path relativePath = safeRelativePath(relative);
            Path target = root.resolve(relativePath).normalize();
            if (!target.startsWith(root)) throw new ArchiveException("Resource path escapes the data directory");
            ensureNoSymlinkParents(root, target);
            String targetRelative = relative;
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) && prepared.hashes.get(archiveName).equals(sha256(target))) {
                    remapped.put(relative, targetRelative);
                    continue;
                }
                String hash = prepared.hashes.get(archiveName);
                String leaf = relativePath.getFileName().toString();
                String suffix = leaf.lastIndexOf('.') > 0 ? leaf.substring(leaf.lastIndexOf('.')) : "";
                String stem = leaf.substring(0, leaf.length() - suffix.length());
                targetRelative = "library/imported/" + stem + "-" + hash.substring(0, 12) + suffix;
                target = root.resolve(targetRelative).normalize();
                int duplicate = 1;
                while (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !prepared.hashes.get(archiveName).equals(sha256(target))) {
                    targetRelative = "library/imported/" + stem + "-" + hash.substring(0, 12) + "-" + duplicate++ + suffix;
                    target = root.resolve(targetRelative).normalize();
                }
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    remapped.put(relative, targetRelative);
                    continue;
                }
            }
            ensureNoSymlinkParents(root, target);
            copyAtomically(prepared.staging.resolve(archiveName), target);
            remapped.put(relative, targetRelative);
        }
    }

    public static final class PreparedImport implements AutoCloseable {
        private final Path staging;
        private final Map<String, Object> config;
        private final Map<String, String> hashes;
        private final String kind;
        private final int soundCount, profileCount, bindingCount;
        private boolean closed;
        private PreparedImport(Path staging, Map<String, Object> config, Map<String, String> hashes, String kind,
                               int soundCount, int profileCount, int bindingCount) {
            this.staging = staging; this.config = config; this.hashes = Map.copyOf(hashes);
            this.kind = kind;
            this.soundCount = soundCount; this.profileCount = profileCount; this.bindingCount = bindingCount;
        }
        public boolean profileArchive() { return "profile".equals(kind); }
        public boolean soundSetArchive() { return "sound-set".equals(kind); }
        public String kind() { return kind; }
        public int soundCount() { return soundCount; }
        public int profileCount() { return profileCount; }
        public int bindingCount() { return bindingCount; }
        public int assetCount() { return (int) hashes.keySet().stream().filter(n -> n.startsWith("data/")).count(); }
        public List<String> outputSelectors() {
            Set<String> selectors = new java.util.TreeSet<>();
            Map<String, Object> settings = objectMap(config.get("settings"));
            addOutputSelector(selectors, settings.get("output"));
            for (Map<String, Object> profile : maps(config.get("profiles"))) addOutputSelector(selectors, profile.get("output"));
            for (Map<String, Object> binding : maps(config.get("bindings"))) addOutputSelector(selectors, binding.get("output"));
            return List.copyOf(selectors);
        }
        public List<String> unavailableOutputSelectors(List<String> availableOutputs) {
            Set<String> available = new LinkedHashSet<>(availableOutputs);
            return outputSelectors().stream().filter(selector -> !available.contains(selector)).toList();
        }
        /** Remaps only explicitly selected missing selectors in a full backup; profile packages are portable already. */
        public void remapOutputSelectors(Map<String, String> remapping, List<String> availableOutputs) throws IOException {
            if (closed) throw new ArchiveException("Prepared import has already been closed");
            if (!"full".equals(kind)) throw new ArchiveException("Output remapping is only available for full backups");
            if (remapping == null || remapping.isEmpty()) return;
            Set<String> existing = new LinkedHashSet<>(outputSelectors());
            Set<String> available = new LinkedHashSet<>(availableOutputs);
            for (var entry : remapping.entrySet()) {
                if (!existing.contains(entry.getKey())) throw new ArchiveException("Archive output selector does not exist: " + entry.getKey());
                if (entry.getValue() == null || !available.contains(entry.getValue()))
                    throw new ArchiveException("Selected replacement output is not available: " + entry.getValue());
            }

            Map<String, Object> updated = deepCopy(config);
            Map<String, Object> settings = objectMap(updated.get("settings"));
            remapOutputField(settings, remapping);
            for (Map<String, Object> profile : maps(updated.get("profiles"))) remapOutputField(profile, remapping);
            for (Map<String, Object> binding : maps(updated.get("bindings"))) remapOutputField(binding, remapping);
            List<String> errors = ConfigValidator.validate(updated);
            if (!errors.isEmpty()) throw new ArchiveException("Remapped configuration is invalid: " + String.join("; ", errors));
            config.clear();
            config.putAll(updated);
        }
        @Override public void close() { if (!closed) { closed = true; deleteTree(staging); } }
    }

    private static void addOutputSelector(Set<String> selectors, Object raw) {
        if (raw instanceof String selector && !selector.isBlank()
                && !Set.of("system_default", "profile_default").contains(selector)) selectors.add(selector);
    }

    private static void remapOutputField(Map<String, Object> record, Map<String, String> remapping) {
        Object raw = record.get("output");
        if (raw instanceof String selector && remapping.containsKey(selector)) record.put("output", remapping.get(selector));
    }

    private static ProfileMerge mergeProfile(Map<String, Object> destination, Map<String, Object> source) throws IOException {
        List<Map<String, Object>> sourceProfiles = maps(source.get("profiles"));
        if (sourceProfiles.size() != 1) throw new ArchiveException("A profile archive must contain exactly one profile");

        Map<String, Object> merged = deepCopy(destination);
        List<Map<String, Object>> destinationSounds = maps(merged.get("sounds"));
        List<Map<String, Object>> destinationEvents = maps(merged.get("event_types"));
        List<Map<String, Object>> destinationGroups = maps(merged.get("groups"));
        List<Map<String, Object>> destinationBindings = maps(merged.get("bindings"));
        List<Map<String, Object>> destinationCombos = maps(merged.get("combos"));
        List<Map<String, Object>> destinationProfiles = maps(merged.get("profiles"));

        List<Map<String, Object>> sourceSounds = maps(source.get("sounds"));
        List<Map<String, Object>> sourceEvents = maps(source.get("event_types"));
        List<Map<String, Object>> sourceGroups = maps(source.get("groups"));
        List<Map<String, Object>> sourceBindings = maps(source.get("bindings"));
        List<Map<String, Object>> sourceCombos = maps(source.get("combos"));

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
        Map<String, String> soundIds = allocateIds(soundsToImport, ids(destinationSounds));
        Map<String, String> eventIds = allocateIds(sourceEvents, ids(destinationEvents));
        Map<String, String> bindingIds = allocateIds(sourceBindings, ids(destinationBindings));
        Map<String, String> comboIds = allocateIds(sourceCombos, ids(destinationCombos));
        Map<String, String> profileIds = allocateIds(sourceProfiles, ids(destinationProfiles));
        Map<String, String> groupIds = new LinkedHashMap<>();
        Set<String> destinationGroupIds = ids(destinationGroups);
        for (Map<String, Object> group : sourceGroups) {
            String id = String.valueOf(group.get("id"));
            if (DefaultConfig.BUILTIN_GROUPS.containsKey(id)) {
                if (!destinationGroupIds.contains(id)) throw new ArchiveException("Destination is missing protected group " + id);
                groupIds.put(id, id);
            } else {
                if (Boolean.TRUE.equals(group.get("protected")))
                    throw new ArchiveException("Profile archive contains an unsupported protected group " + id);
                groupIds.put(id, allocateId(id, destinationGroupIds));
            }
        }

        List<Map<String, Object>> importedSounds = remapRecords(soundsToImport, soundIds);
        for (Map<String, Object> sound : importedSounds) {
            sound.put("builtin", false);
            remapReference(sound, "copy_of", soundIds, ids(destinationSounds));
            remapReference(sound, "fragment_of", soundIds, ids(destinationSounds));
        }

        List<Map<String, Object>> importedEvents = remapRecords(sourceEvents, eventIds);
        List<Map<String, Object>> importedCombos = remapRecords(sourceCombos, comboIds);
        for (Map<String, Object> combo : importedCombos) remapReference(combo, "event", eventIds, ids(destinationEvents));

        List<Map<String, Object>> importedGroups = new ArrayList<>();
        for (Map<String, Object> sourceGroup : sourceGroups) {
            String originalId = String.valueOf(sourceGroup.get("id"));
            if (DefaultConfig.BUILTIN_GROUPS.containsKey(originalId)) continue;
            Map<String, Object> group = deepCopy(sourceGroup);
            group.put("id", groupIds.get(originalId));
            remapReference(group, "default_sound", soundIds, Set.of());
            importedGroups.add(group);
        }

        List<Map<String, Object>> importedBindings = remapRecords(sourceBindings, bindingIds);
        Set<String> allGroupIds = new LinkedHashSet<>(destinationGroupIds);
        allGroupIds.addAll(groupIds.values());
        for (Map<String, Object> binding : importedBindings) {
            remapReference(binding, "sound", soundIds, ids(destinationSounds));
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
                        && !ids(destinationEvents).contains(event))
                    throw new ArchiveException("Profile binding references a missing event " + event);
            }
            binding.put("output", "profile_default");
        }

        Map<String, Object> importedProfile = deepCopy(sourceProfiles.getFirst());
        importedProfile.put("id", profileIds.get(String.valueOf(importedProfile.get("id"))));
        importedProfile.put("output", "system_default");
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

    private static Map<String, String> selectedAssetHashes(Map<String, Object> importedConfig, Set<String> soundIds,
                                                            Map<String, String> archiveHashes) throws IOException {
        TreeMap<String, String> selected = new TreeMap<>();
        for (Map<String, Object> sound : maps(importedConfig.get("sounds"))) {
            if (!soundIds.contains(String.valueOf(sound.get("id")))) continue;
            for (String field : List.of("path", "original_path")) {
                Object value = sound.get(field);
                if (!(value instanceof String path)) continue;
                String archiveName = "data/" + safeRelativePath(path).toString().replace('\\', '/');
                String hash = archiveHashes.get(archiveName);
                if (hash == null) throw new ArchiveException("Archive is missing sound resource " + path);
                selected.put(archiveName, hash);
            }
        }
        return selected;
    }

    private static Map<String, Object> mapOfSounds(List<Map<String, Object>> sounds) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("sounds", sounds);
        return config;
    }

    private static Map<String, String> allocateIds(List<Map<String, Object>> records, Set<String> used) throws IOException {
        Map<String, String> allocated = new LinkedHashMap<>();
        Set<String> namespace = new LinkedHashSet<>(used);
        for (Map<String, Object> record : records) {
            String id = String.valueOf(record.get("id"));
            allocated.put(id, allocateId(id, namespace));
        }
        return allocated;
    }

    private static String allocateId(String id, Set<String> used) throws IOException {
        if (used.add(id)) return id;
        String base = "import-" + id.replaceAll("[^A-Za-z0-9._-]", "-");
        if (base.equals("import-")) base = "import-profile";
        String candidate = base;
        for (int suffix = 2; !used.add(candidate); suffix++) candidate = base + "-" + suffix;
        return candidate;
    }

    private static Set<String> ids(List<Map<String, Object>> records) {
        Set<String> ids = new LinkedHashSet<>();
        records.forEach(record -> { if (record.get("id") instanceof String id) ids.add(id); });
        return ids;
    }

    private static List<Map<String, Object>> remapRecords(List<Map<String, Object>> source, Map<String, String> ids) {
        List<Map<String, Object>> result = new ArrayList<>(source.size());
        for (Map<String, Object> record : source) {
            Map<String, Object> copy = deepCopy(record);
            copy.put("id", ids.get(String.valueOf(record.get("id"))));
            result.add(copy);
        }
        return result;
    }

    private static void remapReference(Map<String, Object> record, String field, Map<String, String> remapping,
                                       Set<String> existingIds) throws IOException {
        Object raw = record.get(field);
        if (raw == null) return;
        String reference = String.valueOf(raw);
        String mapped = remapping.get(reference);
        if (mapped != null) record.put(field, mapped);
        else if (!existingIds.contains(reference)) throw new ArchiveException("Imported " + field + " references missing ID " + reference);
    }

    private record ProfileMerge(Map<String, Object> config, List<Map<String, Object>> sounds, Set<String> soundIds) {}

    private static SoundSetMerge mergeSoundSet(Map<String, Object> destination, Map<String, Object> source) throws IOException {
        List<Map<String, Object>> sourceSounds = maps(source.get("sounds"));
        if (sourceSounds.isEmpty()) throw new ArchiveException("Sound-set archive contains no sounds");
        Map<String, Object> merged = deepCopy(destination);
        List<Map<String, Object>> destinationSounds = maps(merged.get("sounds"));
        Set<String> destinationIds = ids(destinationSounds);
        Map<String, String> soundIds = allocateIds(sourceSounds, destinationIds);
        List<Map<String, Object>> importedSounds = remapRecords(sourceSounds, soundIds);
        for (Map<String, Object> sound : importedSounds) {
            sound.put("builtin", false);
            remapReference(sound, "copy_of", soundIds, destinationIds);
            remapReference(sound, "fragment_of", soundIds, destinationIds);
        }
        destinationSounds.addAll(importedSounds);
        List<String> errors = ConfigValidator.validate(merged);
        if (!errors.isEmpty()) throw new ArchiveException("Could not merge sound set: " + String.join("; ", errors));
        return new SoundSetMerge(merged, importedSounds, new LinkedHashSet<>(soundIds.values()));
    }

    private record SoundSetMerge(Map<String, Object> config, List<Map<String, Object>> sounds, Set<String> soundIds) {}

    private static TreeMap<String, Path> referencedAssets(Map<String, Object> config, Path root) throws IOException {
        TreeMap<String, Path> assets = new TreeMap<>();
        Object soundsValue = config.get("sounds");
        if (!(soundsValue instanceof List<?> sounds)) return assets;
        for (Object value : sounds) if (value instanceof Map<?, ?> sound) {
            for (String field : List.of("path", "original_path")) {
                Object raw = sound.get(field);
                if (raw == null) continue;
                if (!(raw instanceof String relative)) throw new ArchiveException("Sound " + field + " must be a relative path");
                Path rel = safeRelativePath(relative);
                Path file = root.resolve(rel).normalize();
                if (!file.startsWith(root) || !regularFileWithoutSymlinks(root, file)) throw new ArchiveException("Sound resource is missing or outside the app data folder: " + relative);
                assets.put(rel.toString().replace('\\', '/'), file);
            }
        }
        return assets;
    }

    private static void verifySoundReferences(Map<String, Object> config, Map<String, String> expected) throws IOException {
        Object soundsValue = config.get("sounds");
        if (!(soundsValue instanceof List<?> sounds)) return;
        for (Object value : sounds) if (value instanceof Map<?, ?> sound) for (String field : List.of("path", "original_path")) {
            Object raw = sound.get(field);
            if (raw == null) continue;
            if (!(raw instanceof String relative)) throw new ArchiveException("Sound " + field + " must be a relative path");
            String name = "data/" + safeRelativePath(relative).toString().replace('\\', '/');
            if (!expected.containsKey(name)) throw new ArchiveException("Archive is missing sound resource " + relative);
        }
    }

    private static void remapSoundPaths(Map<String, Object> config, Map<String, String> remapped) {
        Object soundsValue = config.get("sounds");
        if (!(soundsValue instanceof List<?> sounds)) return;
        for (Object value : sounds) if (value instanceof Map<?, ?> soundRaw) {
            @SuppressWarnings("unchecked") Map<String, Object> sound = (Map<String, Object>) soundRaw;
            for (String field : List.of("path", "original_path")) {
                Object raw = sound.get(field);
                if (raw instanceof String relative && remapped.containsKey(relative)) sound.put(field, remapped.get(relative));
            }
        }
    }

    private static void disableGlobalSources(Map<String, Object> config) {
        Object settingsValue = config.get("settings");
        if (settingsValue instanceof Map<?, ?> settingsRaw) {
            @SuppressWarnings("unchecked") Map<String, Object> settings = (Map<String, Object>) settingsRaw;
            Object inputValue = settings.get("input");
            if (inputValue instanceof Map<?, ?> inputRaw) {
                @SuppressWarnings("unchecked") Map<String, Object> input = (Map<String, Object>) inputRaw;
                input.put("enable_keyboard", false);
                input.put("enable_mouse", false);
                input.put("enable_gnome", false);
            }
        }
        Object apiValue = config.get("api");
        if (apiValue instanceof Map<?, ?> apiRaw) {
            @SuppressWarnings("unchecked") Map<String, Object> api = (Map<String, Object>) apiRaw;
            api.put("enabled", false);
        }
    }

    private static boolean regularFileWithoutSymlinks(Path root, Path file) throws IOException {
        Path current = root;
        for (Path part : root.relativize(file)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) return false;
        }
        return Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS);
    }
    private static void validateArchivePath(String raw) throws IOException {
        if (raw == null || raw.isBlank() || raw.contains("\\") || raw.contains(":") || raw.startsWith("/")) throw new ArchiveException("Unsafe archive path");
        Path path = Path.of(raw).normalize();
        if (path.isAbsolute() || path.startsWith("..") || !path.toString().replace('\\', '/').equals(raw)) throw new ArchiveException("Unsafe archive path: " + raw);
        if (!(raw.equals("manifest.json") || raw.equals("config.yaml") || raw.startsWith("data/"))) throw new ArchiveException("Unexpected file in archive: " + raw);
    }
    private static Path safeRelativePath(String raw) throws IOException {
        if (raw == null || raw.isBlank() || raw.contains("\\") || raw.contains(":") || raw.startsWith("/")) throw new ArchiveException("Sound paths must be relative paths inside the app data folder");
        Path path = Path.of(raw).normalize();
        if (path.isAbsolute() || path.startsWith("..") || !path.toString().equals(raw)) throw new ArchiveException("Unsafe sound path: " + raw);
        return path;
    }
    private static void put(ZipOutputStream zip, String name, byte[] bytes) throws IOException { zip.putNextEntry(new ZipEntry(name)); zip.write(bytes); zip.closeEntry(); }
    private static void put(ZipOutputStream zip, String name, Path path) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        try (InputStream input = Files.newInputStream(path)) { input.transferTo(zip); }
        zip.closeEntry();
    }
    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) { byte[] buffer = new byte[16 * 1024]; int count; while ((count = input.read(buffer)) >= 0) if (count > 0) digest.update(buffer, 0, count); }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void copyAtomically(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), ".restore-", ".tmp");
        try {
            Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING);
            try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { throw new ArchiveException("Resource filesystem does not support atomic replacement", e); }
        } finally { Files.deleteIfExists(temp); }
    }
    private static void ensureNoSymlinkParents(Path root, Path target) throws IOException {
        Path current = root;
        for (Path part : root.relativize(target.getParent())) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new ArchiveException("Resource path traverses a symbolic link: " + current);
        }
    }
    private static int number(Object value, int fallback) { return value instanceof Number n ? n.intValue() : fallback; }
    private static int count(Map<String, Object> config, String key) { return config.get(key) instanceof List<?> list ? list.size() : 0; }
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> maps(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) (List<?>) list : List.of();
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> objectMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : new LinkedHashMap<>();
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> deepCopy(Map<String, Object> source) { return (Map<String, Object>) copy(source); }
    private static Object copy(Object value) {
        if (value instanceof Map<?, ?> map) { Map<String, Object> out = new LinkedHashMap<>(); map.forEach((k, v) -> out.put(String.valueOf(k), copy(v))); return out; }
        if (value instanceof List<?> list) { List<Object> out = new ArrayList<>(list.size()); list.forEach(v -> out.add(copy(v))); return out; }
        return value;
    }
    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) { paths.sorted(Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (IOException ignored) {} }); }
        catch (IOException ignored) {}
    }
    public static final class ArchiveException extends IOException {
        public ArchiveException(String message) { super(message); }
        public ArchiveException(String message, Throwable cause) { super(message, cause); }
    }
}
