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
            assertEquals(4,((Number)migrated.get("schema_version")).intValue());assertEquals(DefaultConfig.BUILTIN_GROUPS.get("builtin.letters"),fixed.get("members"));assertEquals(List.of(),ConfigValidator.validate(migrated));assertTrue(Files.readString(json).contains("KEY_A"));assertFalse(Files.readString(json).contains("KEY_AA"));assertTrue(Files.exists(temp.resolve("config.yaml.before-json")));
            assertEquals("api_test",((Map<?,?>)((List<?>)migrated.get("bindings")).getLast()).get("event"));
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
}
