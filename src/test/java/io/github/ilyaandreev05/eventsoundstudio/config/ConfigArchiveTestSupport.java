package io.github.ilyaandreev05.eventsoundstudio.config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import static org.junit.jupiter.api.Assertions.*;

abstract class ConfigArchiveTestSupport {
    @TempDir protected Path temp;

    @SuppressWarnings("unchecked") protected static List<Map<String, Object>> maps(Object value) { return (List<Map<String, Object>>) value; }
    protected static Map<String, Object> readManifest(Path archive) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if ("manifest.json".equals(entry.getName()))
                    return new ObjectMapper().readValue(zip.readAllBytes(), new TypeReference<>() {});
                zip.closeEntry();
            }
        }
        throw new IllegalStateException("manifest.json is missing");
    }
    protected static void rewriteManifest(Path source, Path destination, Consumer<Map<String, Object>> edit) throws Exception {
        ObjectMapper json = new ObjectMapper();
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(source))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), zip.readAllBytes());
                zip.closeEntry();
            }
        }
        Map<String, Object> manifest = json.readValue(entries.get("manifest.json"), new TypeReference<>() {});
        edit.accept(manifest);
        if(manifest.get("version") instanceof Number version&&version.intValue()<3&&entries.containsKey("config.json")){
            byte[] document=entries.remove("config.json");entries.put("config.json",document);manifest.remove("config_entry");
            @SuppressWarnings("unchecked") Map<String,String> files=(Map<String,String>)manifest.get("files");String digest=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(document));files.remove("config.json");files.put("config.json",digest);
        }
        entries.put("manifest.json", json.writeValueAsBytes(manifest));
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(destination))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
    }
    protected static Map<String, Object> sound(String id, String path) {
        return DefaultConfig.map("id", id, "name", id, "path", path, "format", "pcm_wav", "volume", 0.7,
                "tags", List.of("test"), "builtin", false);
    }
}
