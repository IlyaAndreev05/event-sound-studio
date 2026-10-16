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
        Path file=temp.resolve("config.yaml");Map<String,Object> config=DefaultConfig.create();config.put("schema_version",2);
        @SuppressWarnings("unchecked") List<Map<String,Object>> groups=(List<Map<String,Object>>)config.get("groups");Map<String,Object> letters=groups.stream().filter(g->"builtin.letters".equals(g.get("id"))).findFirst().orElseThrow();
        List<String> malformed=new ArrayList<>();for(char c='A';c<='Z';c++)malformed.add("KEY_A"+c);letters.put("members",malformed);
        new ObjectMapper(new YAMLFactory()).writeValue(file.toFile(),config);
        try(ConfigStore store=new ConfigStore(file)){
            Map<String,Object> migrated=store.snapshot();@SuppressWarnings("unchecked") List<Map<String,Object>> fixedGroups=(List<Map<String,Object>>)migrated.get("groups");Map<String,Object> fixed=fixedGroups.stream().filter(g->"builtin.letters".equals(g.get("id"))).findFirst().orElseThrow();
            assertEquals(DefaultConfig.BUILTIN_GROUPS.get("builtin.letters"),fixed.get("members"));assertEquals(List.of(),ConfigValidator.validate(migrated));assertTrue(String.join("\n",Files.readAllLines(file)).contains("KEY_A"));assertFalse(Files.readString(file).contains("KEY_AA"));
            assertEquals("api_test",((Map<?,?>)((List<?>)migrated.get("bindings")).getLast()).get("event"));
        }
    }
}
