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

class ConfigArchiveSafetyTest extends ConfigArchiveTestSupport {
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
}
