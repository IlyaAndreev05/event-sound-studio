package io.github.ilyaandreev05.eventsoundstudio.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;

/** Rebinds only the untouched stock right-click WAV; edited and intentional custom cues stay intact. */
final class StandardClickMigration {
    private static final String OLD_SOUND_ID = "octine-right-click";
    private static final String SINGLE_SOUND_ID = "octine-right-click-single";
    private static final String OLD_SOUND_PATH = "assets/sounds/octine-right-click.wav";
    private static final String ARCHIVED_RESOURCE = "/assets/sounds/archive/0.2.2/octine-right-click.wav";

    private StandardClickMigration() { }

    static boolean apply(Map<String, Object> root, Path dataDirectory) {
        if (dataDirectory == null) return false;
        Map<String, Object> stockSound = maps(root.get("sounds")).stream()
                .filter(sound -> OLD_SOUND_ID.equals(sound.get("id")))
                .findFirst().orElse(null);
        if (stockSound == null || !Boolean.TRUE.equals(stockSound.get("builtin"))
                || !OLD_SOUND_PATH.equals(stockSound.get("path"))
                || stockSound.containsKey("original_path") || stockSound.containsKey("copy_of")) return false;

        Path rootPath = dataDirectory.toAbsolutePath().normalize();
        Path source = rootPath.resolve(OLD_SOUND_PATH).normalize();
        if (!source.startsWith(rootPath) || Files.isSymbolicLink(source)
                || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
                || !matchesPackagedOriginal(source)) return false;

        Map<String, Object> defaultBinding = maps(root.get("bindings")).stream()
                .filter(binding -> "mouse-right-click".equals(binding.get("id")))
                .findFirst().orElse(null);
        if (defaultBinding == null
                || !"button:BTN_RIGHT:press".equals(defaultBinding.get("event"))
                || !OLD_SOUND_ID.equals(defaultBinding.get("sound"))) return false;
        defaultBinding.put("sound", SINGLE_SOUND_ID);
        return true;
    }

    private static boolean matchesPackagedOriginal(Path candidate) {
        try (InputStream packaged = StandardClickMigration.class.getResourceAsStream(ARCHIVED_RESOURCE)) {
            if (packaged == null) return false;
            byte[] expected = packaged.readAllBytes();
            byte[] actual = Files.readAllBytes(candidate);
            return MessageDigest.isEqual(sha256(expected), sha256(actual));
        } catch (IOException | NoSuchAlgorithmException ignored) {
            return false;
        }
    }

    private static byte[] sha256(byte[] value) throws NoSuchAlgorithmException {
        return MessageDigest.getInstance("SHA-256").digest(value);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }
}
