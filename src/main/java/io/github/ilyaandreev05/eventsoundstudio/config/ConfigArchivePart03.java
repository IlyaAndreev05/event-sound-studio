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

abstract class ConfigArchivePart03 extends ConfigArchivePart02 {
    public static void install(PreparedImport prepared, Path dataDirectory, ConfigStore store) throws IOException, ConfigStore.ConfigValidationException, ConfigStore.ConfigConflictException {
        if (prepared.closed) throw new ArchiveException("Prepared import has already been closed");
        Path root = dataDirectory.toAbsolutePath().normalize();
        ConfigStore.VersionedSnapshot baseSnapshot=store.versionedSnapshot();
        Map<String, Object> importConfig;
        Map<String, Object> installedConfig;
        Set<String> importSoundIds = null;
        if (prepared.profileArchive()) {
            ProfileMerge merge = ConfigArchive.mergeProfile(baseSnapshot.config(), prepared.config);
            importConfig = ConfigArchive.mapOfSounds(merge.sounds());
            installedConfig = merge.config();
            importSoundIds = merge.soundIds();
        } else if (prepared.soundSetArchive()) {
            SoundSetMerge merge = ConfigArchive.mergeSoundSet(baseSnapshot.config(), prepared.config);
            importConfig = ConfigArchive.mapOfSounds(merge.sounds());
            installedConfig = merge.config();
            importSoundIds = merge.soundIds();
        } else {
            importConfig = ConfigArchive.deepCopy(prepared.config);
            installedConfig = importConfig;
        }

        Map<String, String> hashesToInstall = prepared.profileArchive() || prepared.soundSetArchive()
                ? ConfigArchive.selectedAssetHashes(importConfig, importSoundIds, prepared.hashes)
                : prepared.hashes;
        Map<String, String> remapped = new LinkedHashMap<>();
        ConfigArchive.copyAssets(prepared, root, hashesToInstall, remapped);
        ConfigArchive.remapSoundPaths(importConfig, remapped);
        if (prepared.profileArchive() || prepared.soundSetArchive()) {
            List<String> errors = ConfigValidator.validate(installedConfig);
            if (!errors.isEmpty()) throw new ArchiveException("Merged import configuration is invalid: " + String.join("; ", errors));
        } else {
            ConfigArchive.disableGlobalSources(installedConfig);
        }
        store.save(installedConfig,baseSnapshot.revision());
    }

    static void copyAssets(PreparedImport prepared, Path root, Map<String, String> hashes,
                                   Map<String, String> remapped) throws IOException {
        for (String archiveName : hashes.keySet()) {
            if (!archiveName.startsWith("data/")) continue;
            String relative = archiveName.substring("data/".length());
            Path relativePath = ConfigArchive.safeRelativePath(relative);
            Path target = root.resolve(relativePath).normalize();
            if (!target.startsWith(root)) throw new ArchiveException("Resource path escapes the data directory");
            ConfigArchive.ensureNoSymlinkParents(root, target);
            String targetRelative = relative;
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) && prepared.hashes.get(archiveName).equals(ConfigArchive.sha256(target))) {
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
                while (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !prepared.hashes.get(archiveName).equals(ConfigArchive.sha256(target))) {
                    targetRelative = "library/imported/" + stem + "-" + hash.substring(0, 12) + "-" + duplicate++ + suffix;
                    target = root.resolve(targetRelative).normalize();
                }
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    remapped.put(relative, targetRelative);
                    continue;
                }
            }
            ConfigArchive.ensureNoSymlinkParents(root, target);
            ConfigArchive.copyAtomically(prepared.staging.resolve(archiveName), target);
            remapped.put(relative, targetRelative);
        }
    }

    static void addOutputSelector(Set<String> selectors, Object raw) {
        if (raw instanceof String selector && !selector.isBlank()
                && !Set.of("system_default", "profile_default", MixerOutputSelector.AUDIO_MATRIX_EVENTS).contains(selector)) selectors.add(selector);
    }
}
