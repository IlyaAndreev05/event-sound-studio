package io.github.ilyaandreev05.eventsoundstudio.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ConfigStoreConflictTest {
    @TempDir Path temp;

    @Test void jsonStoreMigratesYamlWithAnExactBackup() throws Exception {
        Path legacy=temp.resolve("config.yaml"),json=temp.resolve("config.json");
        Map<String,Object> old=DefaultConfig.create();old.put("schema_version",1);
        byte[] original=new ObjectMapper(new YAMLFactory()).writeValueAsBytes(old);Files.write(legacy,original);
        try(ConfigStore store=new ConfigStore(json,legacy)){
            assertEquals(5,((Number)store.snapshot().get("schema_version")).intValue());
            assertTrue(Files.readString(json).startsWith("{"));
            assertArrayEquals(original,Files.readAllBytes(temp.resolve("config.yaml.before-json")));
            Map<String,Object> saved=store.snapshot();settings(saved).put("master_volume",.41);store.save(saved,store.versionedSnapshot().revision());
            assertTrue(Files.readString(json).startsWith("{"));
        }
    }

    @Test void migrationDoesNotOverwriteExistingBackup() throws Exception {
        Path legacy=temp.resolve("config.yaml"),json=temp.resolve("config.json"),backup=temp.resolve("config.yaml.before-json");
        Map<String,Object> old=DefaultConfig.create();old.put("schema_version",1);new ObjectMapper(new YAMLFactory()).writeValue(legacy.toFile(),old);
        Files.writeString(backup,"keep");assertThrows(java.io.IOException.class,()->new ConfigStore(json,legacy));
        assertFalse(Files.exists(json));assertEquals("keep",Files.readString(backup));assertTrue(Files.exists(legacy));
    }

    @Test void staleGuiSnapshotCannotOverwriteExternalEditAfterWatcherReloadsIt() throws Exception {
        Path file=temp.resolve("config.json");
        try(ConfigStore store=new ConfigStore(file)){
            ConfigStore.VersionedSnapshot stale=store.versionedSnapshot();
            Map<String,Object> external=DefaultConfig.create();
            settings(external).put("master_volume",.31);
            new ObjectMapper().writeValue(file.toFile(),external);
            store.reload();
            byte[] externalBytes=Files.readAllBytes(file);

            settings(stale.config()).put("master_volume",.22);
            assertThrows(ConfigStore.ConfigConflictException.class,()->store.save(stale.config(),stale.revision()));
            assertArrayEquals(externalBytes,Files.readAllBytes(file));
            assertEquals(.31,settings(store.snapshot()).get("master_volume"));
        }
    }

    @Test void invalidJsonReloadLeavesLastKnownGoodConfigAndBytesUntouched() throws Exception {
        Path file=temp.resolve("config.json");
        try(ConfigStore store=new ConfigStore(file)){
            Map<String,Object> lastKnownGood=store.snapshot();
            String invalid="{\"schema_version\": [unterminated\n";
            Files.writeString(file,invalid);

            assertThrows(ConfigStore.ConfigValidationException.class,store::reload);
            assertEquals(lastKnownGood,store.snapshot());
            assertEquals(invalid,Files.readString(file));
            assertTrue(store.diagnostic().startsWith("Config could not be parsed:"));
        }
    }

    @Test void watcherIgnoresOwnSaveButStillReloadsExternalChanges() throws Exception {
        Path file=temp.resolve("config.json");
        try(ConfigStore store=new ConfigStore(file)){
            LinkedBlockingQueue<String> messages=new LinkedBlockingQueue<>();
            store.addListener(event->messages.add(event.message()));
            ConfigStore.VersionedSnapshot snapshot=store.versionedSnapshot();
            settings(snapshot.config()).put("master_volume",.42);
            store.save(snapshot.config(),snapshot.revision());

            assertEquals("Configuration saved",messages.poll(1,TimeUnit.SECONDS));
            assertNull(messages.poll(450,TimeUnit.MILLISECONDS),"the file watcher must not report this app's own save as external");

            Map<String,Object> external=DefaultConfig.create();
            settings(external).put("master_volume",.37);
            new ObjectMapper().writeValue(file.toFile(),external);

            assertEquals("External configuration reloaded",messages.poll(2,TimeUnit.SECONDS));
            assertEquals(.37,settings(store.snapshot()).get("master_volume"));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> settings(Map<String,Object> config){return (Map<String,Object>)config.get("settings");}
}
