package io.github.ilyaandreev05.eventsoundstudio.audio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Discovery is performed only on stream open/reconnect, never on an event producer. */
final class PipeWireEventTarget {
    private PipeWireEventTarget() { }
    static int requireUniqueSink() throws Exception {
        JsonNode graph = snapshot(); int id = -1, matches = 0;
        for (JsonNode object : graph) {
            JsonNode props = object.path("info").path("props");
            if (PipeWireEventOutput.NODE_NAME.equals(props.path("node.name").asText())
                    && "Audio/Sink".equals(props.path("media.class").asText())) { id = object.path("id").asInt(-1); matches++; }
        }
        if (matches != 1 || id < 0) throw new IOException("Explicit audiomatrix_events target is absent or ambiguous; no hardware fallback");
        return id;
    }
    static int linkedStream(int sink) throws Exception {
        JsonNode graph = snapshot(); int stream = -1;
        for (JsonNode object : graph) {
            JsonNode props = object.path("info").path("props");
            if ("eventsoundstudio_pcm".equals(props.path("node.name").asText())
                    && PipeWireEventOutput.APPLICATION_ID.equals(props.path("application.id").asText())) stream = object.path("id").asInt(-1);
        }
        if (stream < 0) return -1;
        for (JsonNode object : graph) {
            JsonNode info = object.path("info");
            if (info.path("output-node-id").asInt(-2) == stream && info.path("input-node-id").asInt(-2) == sink
                    && ("active".equals(info.path("state").asText()) || "paused".equals(info.path("state").asText()))) return stream;
        }
        return -1;
    }
    /** Level restoration is deliberately off the stream-open path; it must never delay an event. */
    static void normalizeLevelAsync(int stream, java.util.function.BooleanSupplier streamAlive) {
        Thread.ofVirtual().name("event-level-normalize").start(() -> {
            try {
                if (!streamAlive.getAsBoolean()) return;
                command("wpctl", "set-mute", Integer.toString(stream), "0");
                if (!streamAlive.getAsBoolean()) return;
                command("wpctl", "set-volume", Integer.toString(stream), "1.0");
            } catch (Exception ignored) {
                // A saved stream gain may still apply; the persistent stream stays usable.
            }
        });
    }
    private static void command(String... args) throws Exception {
        for (int attempt = 0; attempt < 5; attempt++) {
            Process process = new ProcessBuilder(args).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            boolean ok = process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() == 0;
            if (ok) return;
            process.destroyForcibly();
            Thread.sleep(100);
        }
        throw new IOException("Could not normalize the explicit event stream level");
    }
    static JsonNode snapshot() throws Exception {
        Process process = new ProcessBuilder("pw-dump").redirectError(ProcessBuilder.Redirect.DISCARD).start();
        FutureTask<byte[]> bytes = new FutureTask<>(() -> process.getInputStream().readNBytes(4 * 1024 * 1024));
        Thread reader = Thread.ofVirtual().name("event-target-discovery").start(bytes);
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) throw new IOException("PipeWire target discovery timed out");
            byte[] json = bytes.get(1, TimeUnit.SECONDS);
            if (process.exitValue() != 0 || json.length == 4 * 1024 * 1024) throw new IOException("PipeWire target discovery failed");
            return new ObjectMapper().readTree(json);
        } finally { process.destroyForcibly(); process.getInputStream().close(); reader.interrupt(); }
    }
}
