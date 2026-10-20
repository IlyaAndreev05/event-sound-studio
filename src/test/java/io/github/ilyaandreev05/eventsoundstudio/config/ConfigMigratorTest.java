package io.github.ilyaandreev05.eventsoundstudio.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConfigMigratorTest {
    @TempDir Path temp;

    @Test void storeRepairsTheExactLegacyLetterGroupWithoutDroppingConfiguration() throws Exception {
        Path file=temp.resolve("config.yaml"),json=temp.resolve("config.json");Map<String,Object> config=DefaultConfig.create();config.put("schema_version",2);
        @SuppressWarnings("unchecked") List<Map<String,Object>> groups=(List<Map<String,Object>>)config.get("groups");Map<String,Object> letters=groups.stream().filter(g->"builtin.letters".equals(g.get("id"))).findFirst().orElseThrow();
        List<String> malformed=new ArrayList<>();for(char c='A';c<='Z';c++)malformed.add("KEY_A"+c);letters.put("members",malformed);
        new ObjectMapper(new YAMLFactory()).writeValue(file.toFile(),config);
        try(ConfigStore store=new ConfigStore(json,file)){
            Map<String,Object> migrated=store.snapshot();@SuppressWarnings("unchecked") List<Map<String,Object>> fixedGroups=(List<Map<String,Object>>)migrated.get("groups");Map<String,Object> fixed=fixedGroups.stream().filter(g->"builtin.letters".equals(g.get("id"))).findFirst().orElseThrow();
            assertEquals(6,((Number)migrated.get("schema_version")).intValue());assertEquals(DefaultConfig.BUILTIN_GROUPS.get("builtin.letters"),fixed.get("members"));assertEquals(List.of(),ConfigValidator.validate(migrated));assertTrue(Files.readString(json).contains("KEY_A"));assertFalse(Files.readString(json).contains("KEY_AA"));assertTrue(Files.exists(temp.resolve("config.yaml.before-json")));
            assertTrue(((List<?>)migrated.get("bindings")).stream().anyMatch(item -> "api_test".equals(((Map<?,?>)item).get("event"))));
        }
    }

    @Test void migrationKeepsAnActiveProfileOutputForPreviewWhenGlobalOutputWasDefault() {
        Map<String,Object> config=DefaultConfig.create();
        config.put("schema_version",3);
        @SuppressWarnings("unchecked") Map<String,Object> settings=(Map<String,Object>)config.get("settings");
        settings.put("output","system_default");
        settings.remove("preview_output");
        @SuppressWarnings("unchecked") List<Map<String,Object>> profiles=(List<Map<String,Object>>)config.get("profiles");
        profiles.getFirst().put("output","alsa_output.usb-headphones");

        assertTrue(ConfigMigrator.migrateToCurrentSchema(config,true));
        assertEquals("alsa_output.usb-headphones",settings.get("preview_output"));
        assertEquals(io.github.ilyaandreev05.eventsoundstudio.audio.MixerOutputSelector.AUDIO_MATRIX_EVENTS,settings.get("output"));
        assertTrue(ConfigValidator.validate(config).isEmpty());
    }

    @Test void isolatedEventInputCannotBeSelectedForPreview() {
        Map<String,Object> config=DefaultConfig.create();
        @SuppressWarnings("unchecked") Map<String,Object> settings=(Map<String,Object>)config.get("settings");
        settings.put("preview_output",io.github.ilyaandreev05.eventsoundstudio.audio.MixerOutputSelector.AUDIO_MATRIX_EVENTS);

        assertTrue(ConfigValidator.validate(config).stream().anyMatch(error->error.contains("preview_output must not target")));
    }

    @Test void schemaFiveDoesNotReenableExistingChoicesOrInjectIntoPartialArchives() {
        Map<String,Object> config=DefaultConfig.create();
        config.put("schema_version",4);
        @SuppressWarnings("unchecked") List<Map<String,Object>> bindings=(List<Map<String,Object>>)config.get("bindings");
        bindings.stream().filter(b->"function-keys".equals(b.get("id"))).forEach(b->b.put("enabled",false));
        @SuppressWarnings("unchecked") List<Map<String,Object>> profiles=(List<Map<String,Object>>)config.get("profiles");
        profiles.getFirst().put("enabled_bindings",new ArrayList<>(List.of("letters-default")));
        assertTrue(ConfigMigrator.migrateToCurrentSchema(config,true));
        assertEquals(List.of("letters-default"),profiles.getFirst().get("enabled_bindings"));
        assertFalse(ConfigMigrator.migrateToCurrentSchema(config,true));
        assertFalse((Boolean)bindings.stream().filter(b->"function-keys".equals(b.get("id"))).findFirst().orElseThrow().get("enabled"));
        Map<String,Object> partial=DefaultConfig.map("schema_version",4,"sounds",new ArrayList<>(),"bindings",new ArrayList<>());
        assertTrue(ConfigMigrator.migrateToCurrentSchema(partial,false));
        assertEquals(List.of(),partial.get("sounds"));
        assertEquals(List.of(),partial.get("bindings"));
        assertFalse(partial.containsKey("groups"));
    }

    @Test void migrationRebindsOnlyTheExactBundledRightClickWaveform() throws Exception {
        Path data=temp.resolve("exact-stock");Path sound=data.resolve("assets/sounds/octine-right-click.wav");Files.createDirectories(sound.getParent());
        try(var source=getClass().getResourceAsStream("/assets/sounds/archive/0.2.2/octine-right-click.wav")){
            assertNotNull(source);Files.copy(source,sound);
        }
        Map<String,Object> config=DefaultConfig.create();config.put("schema_version",5);setRightClickSound(config,"octine-right-click");

        assertTrue(ConfigMigrator.migrateToCurrentSchema(config,true,data));
        assertEquals(6,config.get("schema_version"));
        assertEquals("octine-right-click-single",rightClick(config).get("sound"));
        assertTrue(Files.exists(sound),"the legacy resource remains available for other bindings and intentional double cues");
    }

    @Test void migrationLeavesModifiedAndCustomRightClickWavsAlone() throws Exception {
        Path data=temp.resolve("custom");Path sound=data.resolve("assets/sounds/octine-right-click.wav");Files.createDirectories(sound.getParent());
        try(var source=getClass().getResourceAsStream("/assets/sounds/archive/0.2.2/octine-right-click.wav")){
            assertNotNull(source);Files.copy(source,sound);
        }
        byte[] changed=Files.readAllBytes(sound);changed[changed.length-1]^=1;Files.write(sound,changed);
        Map<String,Object> config=DefaultConfig.create();config.put("schema_version",5);setRightClickSound(config,"octine-right-click");

        assertTrue(ConfigMigrator.migrateToCurrentSchema(config,true,data));
        assertEquals("octine-right-click",rightClick(config).get("sound"));

        Map<String,Object> custom=DefaultConfig.create();custom.put("schema_version",5);setRightClickSound(custom,"user-right-click");
        assertTrue(ConfigMigrator.migrateToCurrentSchema(custom,true,data));
        assertEquals("user-right-click",rightClick(custom).get("sound"));
    }

    @Test void configStoreMakesAnAtomicFullBackupBeforeTheStockCueMigration() throws Exception {
        Path file=temp.resolve("migration-backup/config.json");Files.createDirectories(file.getParent());
        Map<String,Object> config=DefaultConfig.create();config.put("schema_version",5);setRightClickSound(config,"octine-right-click");
        byte[] original=new ObjectMapper().writeValueAsBytes(config);Files.write(file,original);

        try(ConfigStore store=new ConfigStore(file)){
            assertEquals(6,store.snapshot().get("schema_version"));
            assertEquals("octine-right-click-single",rightClick(store.snapshot()).get("sound"));
        }
        assertArrayEquals(original,Files.readAllBytes(file.resolveSibling("config.json.before-schema-6")));
    }

    @SuppressWarnings("unchecked") private static Map<String,Object> rightClick(Map<String,Object> config){
        return ((List<Map<String,Object>>)config.get("bindings")).stream().filter(binding->"mouse-right-click".equals(binding.get("id"))).findFirst().orElseThrow();
    }
    private static void setRightClickSound(Map<String,Object> config,String sound){rightClick(config).put("sound",sound);}
}
