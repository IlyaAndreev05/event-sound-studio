package io.github.ilyaandreev05.eventsoundstudio.config;

import java.util.LinkedHashMap;
import java.util.Map;

/** Upgrades supported archive manifests to the current, explicit metadata shape. */
final class ArchiveManifestMigrator {
    static final String FORMAT = "event-sound-studio-archive";
    static final String APP = "event-sound-studio";
    static final int CURRENT_VERSION = 2;

    private ArchiveManifestMigrator() {}

    static Map<String, Object> migrate(Map<String, Object> source, int configSchemaVersion)
            throws ConfigArchive.ArchiveException {
        if (!FORMAT.equals(source.get("format")))
            throw new ConfigArchive.ArchiveException("Archive manifest has an unsupported format");
        Integer version = integer(source.get("version"));
        if (version == null || version < 1)
            throw new ConfigArchive.ArchiveException("Archive manifest has an invalid version");
        if (version > CURRENT_VERSION)
            throw new ConfigArchive.ArchiveException("Archive was created by a newer Event Sound Studio version");
        if (configSchemaVersion < 1)
            throw new ConfigArchive.ArchiveException("Archive config has an unsupported schema version");

        Map<String, Object> migrated = copy(source);
        while (version < CURRENT_VERSION) {
            if (version == 1) {
                migrateV1ToV2(migrated, configSchemaVersion);
                version = 2;
            } else {
                throw new ConfigArchive.ArchiveException("No migration is available for archive version " + version);
            }
        }

        if (!APP.equals(migrated.get("app")))
            throw new ConfigArchive.ArchiveException("Archive manifest belongs to another application");
        Integer declaredSchema = integer(migrated.get("config_schema_version"));
        if (declaredSchema == null || declaredSchema != configSchemaVersion)
            throw new ConfigArchive.ArchiveException("Archive manifest config schema does not match config.yaml");
        if (!migrated.containsKey("kind"))
            throw new ConfigArchive.ArchiveException("Archive manifest is missing its kind");
        migrated.put("version", CURRENT_VERSION);
        return migrated;
    }

    private static void migrateV1ToV2(Map<String, Object> manifest, int configSchemaVersion)
            throws ConfigArchive.ArchiveException {
        Object oldApp = manifest.get("app");
        if (oldApp != null && !APP.equals(oldApp))
            throw new ConfigArchive.ArchiveException("Archive manifest belongs to another application");
        Object oldSchema = manifest.get("config_schema_version");
        Integer declaredSchema = oldSchema == null ? null : integer(oldSchema);
        if (oldSchema != null && declaredSchema == null)
            throw new ConfigArchive.ArchiveException("Archive manifest has an invalid config schema version");
        if (declaredSchema != null && declaredSchema != configSchemaVersion)
            throw new ConfigArchive.ArchiveException("Archive manifest config schema does not match config.yaml");

        Object oldKind = manifest.get("kind");
        if (oldKind == null) manifest.put("kind", "full"); // v1 archives before kind was recorded were full backups.
        else if (!(oldKind instanceof String kind) || !validKind(kind))
            throw new ConfigArchive.ArchiveException("Archive manifest has an unsupported kind");
        manifest.put("app", APP);
        manifest.put("config_schema_version", configSchemaVersion);
        manifest.put("version", 2);
    }

    static boolean validKind(String kind) {
        return "full".equals(kind) || "profile".equals(kind) || "sound-set".equals(kind);
    }

    private static Integer integer(Object value) {
        if (!(value instanceof Number number)) return null;
        double raw = number.doubleValue();
        int parsed = number.intValue();
        return Double.isFinite(raw) && raw == parsed ? parsed : null;
    }

    private static Map<String, Object> copy(Map<String, Object> source) {
        return new LinkedHashMap<>(source);
    }
}
