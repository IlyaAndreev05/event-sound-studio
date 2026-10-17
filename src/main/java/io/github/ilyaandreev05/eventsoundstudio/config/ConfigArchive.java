package io.github.ilyaandreev05.eventsoundstudio.config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import io.github.ilyaandreev05.eventsoundstudio.audio.MixerOutputSelector;

public final class ConfigArchive extends ConfigArchivePart06 {
    /** Exports one self-contained profile and its binding/event/group/sound dependencies. */
    /** Packages selected bindings as a portable one-profile archive for later merge import. */
    /** Exports selected sounds and their copy/fragment provenance as a portable sound-set ZIP. */
    public static final class PreparedImport implements AutoCloseable {
        final Path staging;
        final Map<String, Object> config;
        final Map<String, String> hashes;
        final String kind;
        final int soundCount, profileCount, bindingCount;
        boolean closed;
        PreparedImport(Path staging, Map<String, Object> config, Map<String, String> hashes, String kind,
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
            addOutputSelector(selectors, settings.get("preview_output"));
            return List.copyOf(selectors);
        }
        public List<String> unavailableOutputSelectors(List<String> availableOutputs) {
            return outputSelectors().stream().filter(selector -> !MixerOutputSelector.available(selector, availableOutputs)).toList();
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
            remapPreviewOutputField(settings, remapping);
            List<String> errors = ConfigValidator.validate(updated);
            if (!errors.isEmpty()) throw new ArchiveException("Remapped configuration is invalid: " + String.join("; ", errors));
            config.clear();
            config.putAll(updated);
        }
        @Override public void close() { if (!closed) { closed = true; deleteTree(staging); } }
    }
    static void remapPreviewOutputField(Map<String, Object> record, Map<String, String> remapping) {
        Object raw = record.get("preview_output");
        if (raw instanceof String selector && remapping.containsKey(selector)) record.put("preview_output", remapping.get(selector));
    }
    static void remapReference(Map<String, Object> record, String field, Map<String, String> remapping,
                                       Set<String> existingIds) throws IOException {
        Object raw = record.get(field);
        if (raw == null) return;
        String reference = String.valueOf(raw);
        String mapped = remapping.get(reference);
        if (mapped != null) record.put(field, mapped);
        else if (!existingIds.contains(reference)) throw new ArchiveException("Imported " + field + " references missing ID " + reference);
    }
    @SuppressWarnings("unchecked") static List<Map<String, Object>> maps(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) (List<?>) list : List.of();
    }
    @SuppressWarnings("unchecked") static Map<String, Object> objectMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : new LinkedHashMap<>();
    }
    public static final class ArchiveException extends IOException {
        public ArchiveException(String message) { super(message); }
        public ArchiveException(String message, Throwable cause) { super(message, cause); }
    }
}
