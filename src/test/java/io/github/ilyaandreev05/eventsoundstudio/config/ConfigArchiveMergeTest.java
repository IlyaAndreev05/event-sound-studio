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

class ConfigArchiveMergeTest extends ConfigArchiveTestSupport {
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
            assertEquals("octine-click", maps(merged.get("groups")).stream().filter(group -> "builtin.letters".equals(group.get("id"))).findFirst().orElseThrow().get("default_sound"));
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
}
