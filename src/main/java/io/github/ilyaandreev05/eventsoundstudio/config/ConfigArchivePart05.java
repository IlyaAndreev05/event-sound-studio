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

abstract class ConfigArchivePart05 extends ConfigArchivePart04 {
    static Map<String, String> allocateIds(List<Map<String, Object>> records, Set<String> used) throws IOException {
        Map<String, String> allocated = new LinkedHashMap<>();
        Set<String> namespace = new LinkedHashSet<>(used);
        for (Map<String, Object> record : records) {
            String id = String.valueOf(record.get("id"));
            allocated.put(id, ConfigArchive.allocateId(id, namespace));
        }
        return allocated;
    }

    static String allocateId(String id, Set<String> used) throws IOException {
        if (used.add(id)) return id;
        String base = "import-" + id.replaceAll("[^A-Za-z0-9._-]", "-");
        if (base.equals("import-")) base = "import-profile";
        String candidate = base;
        for (int suffix = 2; !used.add(candidate); suffix++) candidate = base + "-" + suffix;
        return candidate;
    }

    static Set<String> ids(List<Map<String, Object>> records) {
        Set<String> ids = new LinkedHashSet<>();
        records.forEach(record -> { if (record.get("id") instanceof String id) ids.add(id); });
        return ids;
    }

    static List<Map<String, Object>> remapRecords(List<Map<String, Object>> source, Map<String, String> ids) {
        List<Map<String, Object>> result = new ArrayList<>(source.size());
        for (Map<String, Object> record : source) {
            Map<String, Object> copy = ConfigArchive.deepCopy(record);
            copy.put("id", ids.get(String.valueOf(record.get("id"))));
            result.add(copy);
        }
        return result;
    }

    static SoundSetMerge mergeSoundSet(Map<String, Object> destination, Map<String, Object> source) throws IOException {
        List<Map<String, Object>> sourceSounds = ConfigArchive.maps(source.get("sounds"));
        if (sourceSounds.isEmpty()) throw new ArchiveException("Sound-set archive contains no sounds");
        Map<String, Object> merged = ConfigArchive.deepCopy(destination);
        List<Map<String, Object>> destinationSounds = ConfigArchive.maps(merged.get("sounds"));
        Set<String> destinationIds = ConfigArchive.ids(destinationSounds);
        Map<String, String> soundIds = ConfigArchive.allocateIds(sourceSounds, destinationIds);
        List<Map<String, Object>> importedSounds = ConfigArchive.remapRecords(sourceSounds, soundIds);
        for (Map<String, Object> sound : importedSounds) {
            sound.put("builtin", false);
            ConfigArchive.remapReference(sound, "copy_of", soundIds, destinationIds);
            ConfigArchive.remapReference(sound, "fragment_of", soundIds, destinationIds);
        }
        destinationSounds.addAll(importedSounds);
        List<String> errors = ConfigValidator.validate(merged);
        if (!errors.isEmpty()) throw new ArchiveException("Could not merge sound set: " + String.join("; ", errors));
        return new SoundSetMerge(merged, importedSounds, new LinkedHashSet<>(soundIds.values()));
    }

    static TreeMap<String, Path> referencedAssets(Map<String, Object> config, Path root) throws IOException {
        TreeMap<String, Path> assets = new TreeMap<>();
        Object soundsValue = config.get("sounds");
        if (!(soundsValue instanceof List<?> sounds)) return assets;
        for (Object value : sounds) if (value instanceof Map<?, ?> sound) {
            for (String field : List.of("path", "original_path")) {
                Object raw = sound.get(field);
                if (raw == null) continue;
                if (!(raw instanceof String relative)) throw new ArchiveException("Sound " + field + " must be a relative path");
                Path rel = ConfigArchive.safeRelativePath(relative);
                Path file = root.resolve(rel).normalize();
                if (!file.startsWith(root) || !ConfigArchive.regularFileWithoutSymlinks(root, file)) throw new ArchiveException("Sound resource is missing or outside the app data folder: " + relative);
                assets.put(rel.toString().replace('\\', '/'), file);
            }
        }
        return assets;
    }

    static void verifySoundReferences(Map<String, Object> config, Map<String, String> expected) throws IOException {
        Object soundsValue = config.get("sounds");
        if (!(soundsValue instanceof List<?> sounds)) return;
        for (Object value : sounds) if (value instanceof Map<?, ?> sound) for (String field : List.of("path", "original_path")) {
            Object raw = sound.get(field);
            if (raw == null) continue;
            if (!(raw instanceof String relative)) throw new ArchiveException("Sound " + field + " must be a relative path");
            String name = "data/" + ConfigArchive.safeRelativePath(relative).toString().replace('\\', '/');
            if (!expected.containsKey(name)) throw new ArchiveException("Archive is missing sound resource " + relative);
        }
    }

    static void remapSoundPaths(Map<String, Object> config, Map<String, String> remapped) {
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

    static void disableGlobalSources(Map<String, Object> config) {
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

    static boolean regularFileWithoutSymlinks(Path root, Path file) throws IOException {
        Path current = root;
        for (Path part : root.relativize(file)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) return false;
        }
        return Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS);
    }

    static void validateArchivePath(String raw) throws IOException {
        if (raw == null || raw.isBlank() || raw.contains("\\") || raw.contains(":") || raw.startsWith("/")) throw new ArchiveException("Unsafe archive path");
        Path path = Path.of(raw).normalize();
        if (path.isAbsolute() || path.startsWith("..") || !path.toString().replace('\\', '/').equals(raw)) throw new ArchiveException("Unsafe archive path: " + raw);
        if (!(raw.equals("manifest.json") || raw.equals("config.yaml") || raw.equals("config.json") || raw.startsWith("data/"))) throw new ArchiveException("Unexpected file in archive: " + raw);
    }

    static Path safeRelativePath(String raw) throws IOException {
        if (raw == null || raw.isBlank() || raw.contains("\\") || raw.contains(":") || raw.startsWith("/")) throw new ArchiveException("Sound paths must be relative paths inside the app data folder");
        Path path = Path.of(raw).normalize();
        if (path.isAbsolute() || path.startsWith("..") || !path.toString().equals(raw)) throw new ArchiveException("Unsafe sound path: " + raw);
        return path;
    }

    static void put(ZipOutputStream zip, String name, byte[] bytes) throws IOException { zip.putNextEntry(new ZipEntry(name)); zip.write(bytes); zip.closeEntry(); }

    static void put(ZipOutputStream zip, String name, Path path) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        try (InputStream input = Files.newInputStream(path)) { input.transferTo(zip); }
        zip.closeEntry();
    }

    static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) { byte[] buffer = new byte[16 * 1024]; int count; while ((count = input.read(buffer)) >= 0) if (count > 0) digest.update(buffer, 0, count); }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
