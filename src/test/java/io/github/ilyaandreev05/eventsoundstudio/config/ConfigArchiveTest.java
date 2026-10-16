package io.github.ilyaandreev05.eventsoundstudio.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ConfigArchiveTest {
    @TempDir Path temp;

    @Test void currentArchiveManifestDeclaresApplicationAndConfigSchema() throws Exception {
        Path sourceDir = temp.resolve("manifest-source");
        Path archive = temp.resolve("manifest.ess.zip");
        try (ConfigStore source = new ConfigStore(sourceDir.resolve("config.json"))) {
            ConfigArchive.exportArchive(archive, DefaultConfig.create(), source.dataDirectory());
        }

        Map<String, Object> manifest = readManifest(archive);
        assertEquals(4, ((Number) manifest.get("version")).intValue());
        assertEquals("config.json",manifest.get("config_entry"));
        assertEquals("event-sound-studio", manifest.get("app"));
        assertEquals(4, ((Number) manifest.get("config_schema_version")).intValue());
        assertEquals("full", manifest.get("kind"));
    }

    @Test void migratesLegacyV1ManifestAndRejectsFutureOrMismatchedVersions() throws Exception {
        Path sourceDir = temp.resolve("legacy-manifest-source");
        Path archive = temp.resolve("current.ess.zip");
        try (ConfigStore source = new ConfigStore(sourceDir.resolve("config.json"))) {
            ConfigArchive.exportArchive(archive, DefaultConfig.create(), source.dataDirectory());
        }

        Path legacy = temp.resolve("legacy-v1.ess.zip");
        rewriteManifest(archive, legacy, manifest -> {
            manifest.put("version", 1);
            manifest.remove("app");
            manifest.remove("config_schema_version");
            manifest.remove("kind");
        });
        try (ConfigArchive.PreparedImport prepared = ConfigArchive.prepare(legacy)) {
            assertEquals("full", prepared.kind());
        }

        Path future = temp.resolve("future.ess.zip");
        rewriteManifest(archive, future, manifest -> manifest.put("version", 999));
        assertThrows(ConfigArchive.ArchiveException.class, () -> ConfigArchive.prepare(future));

        Path mismatch = temp.resolve("schema-mismatch.ess.zip");
        rewriteManifest(archive, mismatch, manifest -> manifest.put("config_schema_version", 7));
        assertThrows(ConfigArchive.ArchiveException.class, () -> ConfigArchive.prepare(mismatch));

        Path malformed = temp.resolve("malformed-schema.ess.zip");
        rewriteManifest(archive, malformed, manifest -> {
            manifest.put("version", 1);
            manifest.put("config_schema_version", "one");
        });
        assertThrows(ConfigArchive.ArchiveException.class, () -> ConfigArchive.prepare(malformed));
    }

    @Test void remapsOnlyMissingOutputsAfterExplicitSelection() throws Exception {
        Path sourceDir = temp.resolve("output-remap-source");
        Path archive = temp.resolve("output-remap.ess.zip");
        Map<String, Object> sourceConfig = DefaultConfig.create();
        ((Map<String,Object>) sourceConfig.get("settings")).put("preview_output", "Old USB DAC");
        try (ConfigStore source = new ConfigStore(sourceDir.resolve("config.json"))) {
            ConfigArchive.exportArchive(archive, sourceConfig, source.dataDirectory());
        }

        Path destinationDir = temp.resolve("output-remap-destination");
        try (ConfigStore destination = new ConfigStore(destinationDir.resolve("config.json"));
             ConfigArchive.PreparedImport prepared = ConfigArchive.prepare(archive)) {
            List<String> available = List.of("system_default", "Family 17h (Models 00h-0fh)");
            assertEquals(List.of("Old USB DAC"), prepared.unavailableOutputSelectors(available));
            assertThrows(ConfigArchive.ArchiveException.class,
                    () -> prepared.remapOutputSelectors(Map.of("Old USB DAC", "Unknown output"), available));
            assertEquals(List.of("Old USB DAC"), prepared.outputSelectors());

            prepared.remapOutputSelectors(Map.of("Old USB DAC", "Family 17h (Models 00h-0fh)"), available);
            ConfigArchive.install(prepared, destination.dataDirectory(), destination);

            Map<String, Object> restored = destination.snapshot();
            assertEquals("Family 17h (Models 00h-0fh)", ((Map<?,?>) restored.get("settings")).get("preview_output"));
            assertEquals("audio_matrix_events", ((Map<?,?>) restored.get("settings")).get("output"));
            assertEquals("profile_default", maps(restored.get("bindings")).stream()
                    .filter(binding -> "custom-test-api".equals(binding.get("id"))).findFirst().orElseThrow().get("output"));
        }
    }

    @Test void legacyOutputNameMatchesOneStableMixerSelectorButNotAmbiguousChoices() throws Exception {
        Path sourceDir = temp.resolve("legacy-output-source");
        Path archive = temp.resolve("legacy-output.ess.zip");
        Map<String, Object> config = DefaultConfig.create();
        ((Map<String,Object>) config.get("settings")).put("preview_output", "USB output");
        try (ConfigStore source = new ConfigStore(sourceDir.resolve("config.json"))) {
            ConfigArchive.exportArchive(archive, config, source.dataDirectory());
        }

        String firstMixer = "java-sound-v1|USB+output|Acme|USB+DAC";
        String secondMixer = "java-sound-v1|USB+output|Other|USB+DAC";
        try (ConfigArchive.PreparedImport prepared = ConfigArchive.prepare(archive)) {
            assertEquals(List.of(), prepared.unavailableOutputSelectors(List.of("system_default", firstMixer)));
            assertEquals(List.of("USB output"), prepared.unavailableOutputSelectors(List.of(firstMixer, secondMixer)));
        }
    }

    @Test void profileArchiveIncludesOnlySelectedBindingsAndTheirSoundAssets() throws Exception {
        Path sourceDir = temp.resolve("profile-source");
        try (ConfigStore source = new ConfigStore(sourceDir.resolve("config.json"))) {
            Map<String, Object> config = DefaultConfig.create();
            Map<String, Object> quiet = maps(config.get("profiles")).stream()
                    .filter(profile -> "quiet".equals(profile.get("id"))).findFirst().orElseThrow();
            quiet.put("enabled_bindings", List.of("custom-test-api"));
            Map<String, Object> binding = maps(config.get("bindings")).stream()
                    .filter(item -> "custom-test-api".equals(item.get("id"))).findFirst().orElseThrow();
            binding.put("sound", "profile-only");

            Path selectedAsset = source.dataDirectory().resolve("library/profile/selected.wav");
            Path unrelatedAsset = source.dataDirectory().resolve("library/profile/unrelated.wav");
            Files.createDirectories(selectedAsset.getParent());
            Files.write(selectedAsset, new byte[]{1, 3, 5, 7});
            Files.write(unrelatedAsset, new byte[]{2, 4, 6, 8});
            maps(config.get("sounds")).add(sound("profile-only", "library/profile/selected.wav"));
            maps(config.get("sounds")).add(sound("unused-sound", "library/profile/unrelated.wav"));
            Path archive = temp.resolve("quiet-profile.ess.zip");

            ConfigArchive.exportProfileArchive(archive, config, source.dataDirectory(), "quiet");

            Map<String, byte[]> entries = new LinkedHashMap<>();
            try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    entries.put(entry.getName(), zip.readAllBytes());
                    zip.closeEntry();
                }
            }
            assertTrue(entries.containsKey("data/library/profile/selected.wav"));
            assertFalse(entries.containsKey("data/library/profile/unrelated.wav"));
            ObjectMapper json = new ObjectMapper();
            Map<String, Object> packaged = json.readValue(entries.get("config.json"), new TypeReference<>() {});
            assertEquals("quiet", packaged.get("active_profile"));
            assertEquals(1, maps(packaged.get("profiles")).size());
            assertEquals("audio_matrix_events", maps(packaged.get("profiles")).getFirst().get("output"));
            assertEquals("system_default", ((Map<?,?>) packaged.get("settings")).get("preview_output"));
            assertEquals(1, maps(packaged.get("bindings")).size());
            assertEquals("profile_default", maps(packaged.get("bindings")).getFirst().get("output"));
            assertEquals(false, ((Map<String, Object>) ((Map<String, Object>) packaged.get("settings")).get("input")).get("enable_keyboard"));
            assertEquals(false, ((Map<String, Object>) packaged.get("api")).get("enabled"));
            try (ConfigArchive.PreparedImport prepared = ConfigArchive.prepare(archive)) {
                assertEquals(1, prepared.profileCount());
                assertEquals(1, prepared.bindingCount());
            }
        }
    }

    @Test void profileImportMergesWithoutReplacingSettingsAndRemapsConflictingIds() throws Exception {
        Path sourceDir = temp.resolve("merge-profile-source");
        Path archive = temp.resolve("merge-profile.ess.zip");
        try (ConfigStore source = new ConfigStore(sourceDir.resolve("config.json"))) {
            Map<String, Object> config = DefaultConfig.create();
            Map<String, Object> quiet = maps(config.get("profiles")).stream()
                    .filter(profile -> "quiet".equals(profile.get("id"))).findFirst().orElseThrow();
            quiet.put("enabled_bindings", List.of("custom-test-api", "game-group-binding"));
            maps(config.get("bindings")).add(DefaultConfig.map("id", "game-group-binding", "name", "Game group",
                    "event", "group:custom.game", "sound", "profile-only", "enabled", true, "priority", 12,
                    "volume", 0.8, "cooldown_ms", 0, "overlap", "mix", "output", "profile_default"));
            Map<String, Object> profileOnly = sound("profile-only", "library/profile/selected.wav");
            profileOnly.put("copy_of", "key-tap");
            maps(config.get("sounds")).add(profileOnly);
            maps(config.get("sounds")).add(sound("unused-sound", "library/profile/unrelated.wav"));
            Map<String, Object> combo = maps(config.get("combos")).getFirst();
            combo.put("keys", List.of("KEY_LEFTCTRL", "KEY_K"));
            combo.put("event", "api_test");
            Path selectedAsset = source.dataDirectory().resolve("library/profile/selected.wav");
            Path unrelatedAsset = source.dataDirectory().resolve("library/profile/unrelated.wav");
            Files.createDirectories(selectedAsset.getParent());
            Files.write(selectedAsset, new byte[]{1, 4, 9, 16});
            Files.write(unrelatedAsset, new byte[]{2, 3, 5, 7});

            ConfigArchive.exportProfileArchive(archive, config, source.dataDirectory(), "quiet");
        }

        Path destinationDir = temp.resolve("merge-profile-destination");
        try (ConfigStore destination = new ConfigStore(destinationDir.resolve("config.json"));
             ConfigArchive.PreparedImport prepared = ConfigArchive.prepare(archive)) {
            assertTrue(prepared.profileArchive());
            ConfigStore.VersionedSnapshot snapshot=destination.versionedSnapshot();
            Map<String, Object> current = snapshot.config();
            Map<String, Object> settings = (Map<String, Object>) current.get("settings");
            settings.put("language", "en");
            settings.put("master_volume", 0.42);
            ((Map<String, Object>) settings.get("input")).put("enable_keyboard", true);
            ((Map<String, Object>) current.get("api")).put("enabled", true);
            ((Map<String, Object>) current.get("source_profiles")).put("keyboard", "quiet");
            maps(current.get("sounds")).add(sound("profile-only", "library/old/profile-only.wav"));
            destination.save(current,snapshot.revision());

            ConfigArchive.install(prepared, destination.dataDirectory(), destination);

            Map<String, Object> merged = destination.snapshot();
            assertEquals("desk", merged.get("active_profile"));
            assertEquals("en", ((Map<String, Object>) merged.get("settings")).get("language"));
            assertEquals(0.42, ((Number) ((Map<String, Object>) merged.get("settings")).get("master_volume")).doubleValue());
            assertEquals(true, ((Map<String, Object>) ((Map<String, Object>) merged.get("settings")).get("input")).get("enable_keyboard"));
            assertEquals(true, ((Map<String, Object>) merged.get("api")).get("enabled"));
            assertEquals("quiet", ((Map<String, Object>) merged.get("source_profiles")).get("keyboard"));

            Map<String, Object> importedProfile = maps(merged.get("profiles")).stream()
                    .filter(profile -> "import-quiet".equals(profile.get("id"))).findFirst().orElseThrow();
            assertEquals("audio_matrix_events", importedProfile.get("output"));
            List<String> importedBindingIds = ((List<?>) importedProfile.get("enabled_bindings")).stream().map(String::valueOf).toList();
            assertEquals(List.of("import-custom-test-api", "game-group-binding"), importedBindingIds);

            Map<String, Object> eventBinding = maps(merged.get("bindings")).stream()
                    .filter(binding -> "import-custom-test-api".equals(binding.get("id"))).findFirst().orElseThrow();
            assertEquals("import-api_test", eventBinding.get("event"));
            assertEquals("import-key-tap", eventBinding.get("sound"));
            assertEquals("profile_default", eventBinding.get("output"));
            Map<String, Object> groupBinding = maps(merged.get("bindings")).stream()
                    .filter(binding -> "game-group-binding".equals(binding.get("id"))).findFirst().orElseThrow();
            assertEquals("group:import-custom.game", groupBinding.get("event"));
            assertEquals("import-profile-only", groupBinding.get("sound"));

            Map<String, Object> importedGroup = maps(merged.get("groups")).stream()
                    .filter(group -> "import-custom.game".equals(group.get("id"))).findFirst().orElseThrow();
            assertEquals("import-key-tap", importedGroup.get("default_sound"));
            assertEquals("key-tap", maps(merged.get("groups")).stream().filter(group -> "builtin.letters".equals(group.get("id"))).findFirst().orElseThrow().get("default_sound"));
            assertFalse(maps(merged.get("sounds")).stream().anyMatch(sound -> "import-soft-click".equals(sound.get("id"))));
            Map<String, Object> importedSound = maps(merged.get("sounds")).stream()
                    .filter(sound -> "import-profile-only".equals(sound.get("id"))).findFirst().orElseThrow();
            assertEquals("import-key-tap", importedSound.get("copy_of"));
            assertEquals(false, maps(merged.get("sounds")).stream().filter(sound -> "import-key-tap".equals(sound.get("id"))).findFirst().orElseThrow().get("builtin"));
            assertArrayEquals(new byte[]{1, 4, 9, 16}, Files.readAllBytes(destinationDir.resolve(String.valueOf(importedSound.get("path")))));
            assertFalse(Files.exists(destinationDir.resolve("library/profile/unrelated.wav")));

            Map<String, Object> importedCombo = maps(merged.get("combos")).stream()
                    .filter(combo -> "import-save".equals(combo.get("id"))).findFirst().orElseThrow();
            assertEquals("import-api_test", importedCombo.get("event"));
        }
    }

    @Test void soundSetImportMergesDependenciesAndRemapsSoundIds() throws Exception {
        Path sourceDir = temp.resolve("sound-set-source");
        Path archive = temp.resolve("sounds.ess.zip");
        try (ConfigStore source = new ConfigStore(sourceDir.resolve("config.json"))) {
            Map<String, Object> config = DefaultConfig.create();
            maps(config.get("sounds")).add(sound("base-tone", "library/set/base.wav"));
            Map<String, Object> derived = sound("derived-tone", "library/set/derived.wav");
            derived.put("fragment_of", "base-tone");
            maps(config.get("sounds")).add(derived);
            maps(config.get("sounds")).add(sound("unrelated-tone", "library/set/unrelated.wav"));
            Path folder = source.dataDirectory().resolve("library/set");
            Files.createDirectories(folder);
            Files.write(folder.resolve("base.wav"), new byte[]{10, 20, 30});
            Files.write(folder.resolve("derived.wav"), new byte[]{40, 50, 60});
            Files.write(folder.resolve("unrelated.wav"), new byte[]{70, 80, 90});

            ConfigArchive.exportSoundSetArchive(archive, config, source.dataDirectory(), Set.of("derived-tone"));
            try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
                Set<String> paths = new java.util.HashSet<>();
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) { paths.add(entry.getName()); zip.closeEntry(); }
                assertTrue(paths.contains("data/library/set/base.wav"));
                assertTrue(paths.contains("data/library/set/derived.wav"));
                assertFalse(paths.contains("data/library/set/unrelated.wav"));
            }
        }

        Path destinationDir = temp.resolve("sound-set-destination");
        try (ConfigStore destination = new ConfigStore(destinationDir.resolve("config.json"));
             ConfigArchive.PreparedImport prepared = ConfigArchive.prepare(archive)) {
            assertTrue(prepared.soundSetArchive());
            assertFalse(prepared.profileArchive());
            assertEquals(2, prepared.soundCount());
            ConfigStore.VersionedSnapshot snapshot=destination.versionedSnapshot();
            Map<String, Object> current = snapshot.config();
            ((Map<String, Object>) current.get("settings")).put("master_volume", 0.37);
            maps(current.get("sounds")).add(sound("base-tone", "library/old/base.wav"));
            maps(current.get("sounds")).add(sound("derived-tone", "library/old/derived.wav"));
            destination.save(current,snapshot.revision());

            ConfigArchive.install(prepared, destination.dataDirectory(), destination);

            Map<String, Object> merged = destination.snapshot();
            assertEquals("desk", merged.get("active_profile"));
            assertEquals(0.37, ((Number) ((Map<String, Object>) merged.get("settings")).get("master_volume")).doubleValue());
            Map<String, Object> importedBase = maps(merged.get("sounds")).stream()
                    .filter(sound -> "import-base-tone".equals(sound.get("id"))).findFirst().orElseThrow();
            Map<String, Object> importedDerived = maps(merged.get("sounds")).stream()
                    .filter(sound -> "import-derived-tone".equals(sound.get("id"))).findFirst().orElseThrow();
            assertEquals("import-base-tone", importedDerived.get("fragment_of"));
            assertEquals(false, importedBase.get("builtin"));
            assertArrayEquals(new byte[]{10, 20, 30}, Files.readAllBytes(destinationDir.resolve(String.valueOf(importedBase.get("path")))));
            assertArrayEquals(new byte[]{40, 50, 60}, Files.readAllBytes(destinationDir.resolve(String.valueOf(importedDerived.get("path")))));
            assertFalse(Files.exists(destinationDir.resolve("library/set/unrelated.wav")));
        }
    }

    @Test void bindingSetArchiveContainsOnlyTheSelectedBindings() throws Exception {
        Path sourceDir = temp.resolve("binding-set-source");
        try (ConfigStore source = new ConfigStore(sourceDir.resolve("config.json"))) {
            Map<String, Object> config = DefaultConfig.create();
            Path asset = source.dataDirectory().resolve("library/binding-set/custom.wav");
            Files.createDirectories(asset.getParent());
            Files.write(asset, new byte[]{3, 1, 4, 1});
            maps(config.get("sounds")).add(sound("binding-set-sound", "library/binding-set/custom.wav"));
            maps(config.get("bindings")).stream().filter(binding -> "custom-test-api".equals(binding.get("id")))
                    .findFirst().orElseThrow().put("sound", "binding-set-sound");
            Path archive = temp.resolve("selected-bindings.ess.zip");

            ConfigArchive.exportBindingSetArchive(archive, config, source.dataDirectory(), Set.of("custom-test-api"));

            try (ConfigArchive.PreparedImport prepared = ConfigArchive.prepare(archive)) {
                assertTrue(prepared.profileArchive());
                assertEquals(1, prepared.profileCount());
                assertEquals(1, prepared.bindingCount());
                assertTrue(prepared.soundCount() >= 1);
            }
        }
    }

    @Test void exportsAndRestoresConfigAndReferencedWavWithoutOverwritingCollision() throws Exception {
        Path sourceDir = temp.resolve("source");
        try (ConfigStore source = new ConfigStore(sourceDir.resolve("config.json"))) {
            Map<String, Object> config = DefaultConfig.create();
            List<Map<String, Object>> sounds = maps(config.get("sounds"));
            Map<String, Object> settings = (Map<String, Object>) config.get("settings");
            ((Map<String, Object>) settings.get("input")).put("enable_keyboard", true);
            ((Map<String, Object>) config.get("api")).put("enabled", true);
            Path custom = sourceDir.resolve("library/imports/custom-original.wav");
            Files.createDirectories(custom.getParent());
            Files.write(custom, new byte[]{1, 2, 3, 4});
            Map<String, Object> sound = new LinkedHashMap<>();
            sound.put("id", "user-custom"); sound.put("name", "Custom"); sound.put("path", "library/imports/custom-original.wav");
            sound.put("original_path", "library/imports/custom-original.wav"); sound.put("format", "pcm_wav"); sound.put("volume", 0.7); sound.put("tags", List.of("custom")); sound.put("builtin", false);
            sounds.add(sound);
            Path archive = temp.resolve("backup.ess.zip");
            ConfigArchive.exportArchive(archive, config, source.dataDirectory());

            Path destinationDir = temp.resolve("destination");
            try (ConfigStore destination = new ConfigStore(destinationDir.resolve("config.json"));
                 ConfigArchive.PreparedImport prepared = ConfigArchive.prepare(archive)) {
                assertEquals(sounds.size(), prepared.soundCount());
                assertEquals(maps(config.get("profiles")).size(), prepared.profileCount());
                assertTrue(prepared.assetCount() > 0);
                Path collision = destinationDir.resolve("library/imports/custom-original.wav");
                Files.createDirectories(collision.getParent());
                Files.write(collision, new byte[]{9, 8, 7});

                ConfigArchive.install(prepared, destination.dataDirectory(), destination);
                assertArrayEquals(new byte[]{9, 8, 7}, Files.readAllBytes(collision));
                Map<String, Object> restored = destination.snapshot();
                Map<String, Object> restoredSound = maps(restored.get("sounds")).stream().filter(s -> "user-custom".equals(s.get("id"))).findFirst().orElseThrow();
                String restoredPath = String.valueOf(restoredSound.get("path"));
                assertTrue(restoredPath.startsWith("library/imported/"));
                assertArrayEquals(new byte[]{1, 2, 3, 4}, Files.readAllBytes(destinationDir.resolve(restoredPath)));
                assertEquals("desk", restored.get("active_profile"));
                Map<String, Object> restoredSettings = (Map<String, Object>) restored.get("settings");
                Map<String, Object> restoredInput = (Map<String, Object>) restoredSettings.get("input");
                assertEquals(false, restoredInput.get("enable_keyboard"));
                assertEquals(false, ((Map<String, Object>) restored.get("api")).get("enabled"));
            }
        }
    }

    @Test void rejectsPathTraversalBeforeWritingOutsideStaging() throws Exception {
        Path archive = temp.resolve("traversal.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("../escape.txt")); zip.write("no".getBytes()); zip.closeEntry();
        }
        assertThrows(ConfigArchive.ArchiveException.class, () -> ConfigArchive.prepare(archive));
        assertFalse(Files.exists(temp.resolve("escape.txt")));
    }

    @Test void checksumMismatchIsRejected() throws Exception {
        Path sourceDir = temp.resolve("checksum-source");
        Path archive = temp.resolve("valid.ess.zip");
        try (ConfigStore source = new ConfigStore(sourceDir.resolve("config.json"))) {
            ConfigArchive.exportArchive(archive, DefaultConfig.create(), source.dataDirectory());
        }
        Path corrupt = temp.resolve("corrupt.ess.zip");
        try (ZipInputStream input = new ZipInputStream(Files.newInputStream(archive)); ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(corrupt))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                byte[] bytes = input.readAllBytes();
                output.putNextEntry(new ZipEntry(entry.getName()));
                if (entry.getName().startsWith("data/")) { bytes[0] ^= 0x7f; output.write(bytes); }
                else output.write(bytes);
                output.closeEntry();
            }
        }
        assertThrows(ConfigArchive.ArchiveException.class, () -> ConfigArchive.prepare(corrupt));
    }

    @SuppressWarnings("unchecked") private static List<Map<String, Object>> maps(Object value) { return (List<Map<String, Object>>) value; }
    private static Map<String, Object> readManifest(Path archive) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if ("manifest.json".equals(entry.getName()))
                    return new ObjectMapper().readValue(zip.readAllBytes(), new TypeReference<>() {});
                zip.closeEntry();
            }
        }
        throw new IllegalStateException("manifest.json is missing");
    }
    private static void rewriteManifest(Path source, Path destination, Consumer<Map<String, Object>> edit) throws Exception {
        ObjectMapper json = new ObjectMapper();
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(source))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), zip.readAllBytes());
                zip.closeEntry();
            }
        }
        Map<String, Object> manifest = json.readValue(entries.get("manifest.json"), new TypeReference<>() {});
        edit.accept(manifest);
        if(manifest.get("version") instanceof Number version&&version.intValue()<3&&entries.containsKey("config.json")){
            byte[] document=entries.remove("config.json");entries.put("config.json",document);manifest.remove("config_entry");
            @SuppressWarnings("unchecked") Map<String,String> files=(Map<String,String>)manifest.get("files");String digest=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(document));files.remove("config.json");files.put("config.json",digest);
        }
        entries.put("manifest.json", json.writeValueAsBytes(manifest));
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(destination))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
    }
    private static Map<String, Object> sound(String id, String path) {
        return DefaultConfig.map("id", id, "name", id, "path", path, "format", "pcm_wav", "volume", 0.7,
                "tags", List.of("test"), "builtin", false);
    }
}
