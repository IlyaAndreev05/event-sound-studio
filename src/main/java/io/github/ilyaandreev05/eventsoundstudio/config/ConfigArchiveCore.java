package io.github.ilyaandreev05.eventsoundstudio.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.util.*;

abstract class ConfigArchiveCore {
    static final int MAX_ENTRIES = 512;
    static final long MAX_UNPACKED_BYTES = 512L * 1024 * 1024;
    static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {};
    static final ObjectMapper JSON = new ObjectMapper();
    static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    static record ProfileMerge(Map<String, Object> config, List<Map<String, Object>> sounds, Set<String> soundIds) {}
    static record SoundSetMerge(Map<String, Object> config, List<Map<String, Object>> sounds, Set<String> soundIds) {}
}
