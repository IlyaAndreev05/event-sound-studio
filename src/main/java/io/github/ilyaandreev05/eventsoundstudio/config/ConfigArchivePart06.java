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

abstract class ConfigArchivePart06 extends ConfigArchivePart05 {
    static void copyAtomically(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), ".restore-", ".tmp");
        try {
            Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING);
            try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { throw new ArchiveException("Resource filesystem does not support atomic replacement", e); }
        } finally { Files.deleteIfExists(temp); }
    }

    static void ensureNoSymlinkParents(Path root, Path target) throws IOException {
        Path current = root;
        for (Path part : root.relativize(target.getParent())) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new ArchiveException("Resource path traverses a symbolic link: " + current);
        }
    }

    static int number(Object value, int fallback) { return value instanceof Number n ? n.intValue() : fallback; }

    static int count(Map<String, Object> config, String key) { return config.get(key) instanceof List<?> list ? list.size() : 0; }

    @SuppressWarnings("unchecked") static Map<String, Object> deepCopy(Map<String, Object> source) { return (Map<String, Object>) ConfigArchive.copy(source); }
    static Object copy(Object value) {
        if (value instanceof Map<?, ?> map) { Map<String, Object> out = new LinkedHashMap<>(); map.forEach((k, v) -> out.put(String.valueOf(k), ConfigArchive.copy(v))); return out; }
        if (value instanceof List<?> list) { List<Object> out = new ArrayList<>(list.size()); list.forEach(v -> out.add(ConfigArchive.copy(v))); return out; }
        return value;
    }

    static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) { paths.sorted(Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (IOException ignored) {} }); }
        catch (IOException ignored) {}
    }
}
