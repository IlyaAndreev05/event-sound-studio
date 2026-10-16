package io.github.ilyaandreev05.eventsoundstudio.audio;

import io.github.ilyaandreev05.eventsoundstudio.config.ConfigStore;
import io.github.ilyaandreev05.eventsoundstudio.config.DefaultConfig;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Opt-in Java Sound smoke. Nonzero PCM is sent only after the process is proven to use /dev/null. */
class AlsaNullOutputSmokeTest {
    private static final String EXPECTED_ALSA_CONFIG = """
            pcm.null {
              type null
            }
            pcm.!default {
              type file
              slave.pcm \"null\"
              file \"/dev/null\"
              format \"raw\"
            }
            """;

    @TempDir Path temp;

    @Test
    @SuppressWarnings("unchecked")
    void eventRouterPreviewUsesOnlyTheProcessLocalNullDevice() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("EVENT_SOUND_STUDIO_ALSA_NULL_SMOKE")),
                "Run scripts/alsa-null-smoke.sh to enable this isolated Java Sound smoke");

        String alsaConfigPath = System.getenv("ALSA_CONFIG_PATH");
        assertTrue(alsaConfigPath != null && !alsaConfigPath.isBlank(), "ALSA_CONFIG_PATH must be set for this process");
        assertEquals(EXPECTED_ALSA_CONFIG, Files.readString(Path.of(alsaConfigPath)),
                "The process-local ALSA config must route the default PCM to /dev/null through the ALSA null PCM");

        Map<String, Object> config = DefaultConfig.create();
        AudioEngine engine = new AudioEngine();
        AtomicReference<String> outputStatus = new AtomicReference<>("");
        CountDownLatch outputReady = new CountDownLatch(1);
        try (ConfigStore store = new ConfigStore(temp.resolve("config.json"));
             SoundLibrary library = new SoundLibrary(store)) {
            library.loadAll((java.util.List<Map<String, Object>>) config.get("sounds")).get(5, TimeUnit.SECONDS);
            EventRouter router = new EventRouter(engine, library);
            router.update(config);
            Map<Integer, String> descriptorsBeforeAudio = processFileDescriptors();

            engine.start(status -> {
                outputStatus.set(status);
                if (status.startsWith("Audio output ready:")) outputReady.countDown();
            });

            assertTrue(outputReady.await(5, TimeUnit.SECONDS), "Java Sound should open the process-local ALSA null output; last status: " + outputStatus.get());
            Map<Integer, String> descriptors = processFileDescriptors();
            assertTrue(hasNewDescriptorToDevNull(descriptorsBeforeAudio, descriptors),
                    "Java Sound must open a new /dev/null descriptor before any nonzero sound is sent; before=" + descriptorsBeforeAudio + ", after=" + descriptors);
            assertFalse(descriptors.values().stream().anyMatch(path -> path.startsWith("/dev/snd/")),
                    "The Java Sound process must not have opened physical ALSA hardware: " + descriptors);

            SoundLibrary.Entry entry = library.get("key-tap");
            assertTrue(entry != null && entry.ready(), "The built-in preview asset must be available");
            assertTrue(hasNonzeroSample(entry.pcm().samples()), "The preview asset must contain nonzero PCM");
            assertTrue(router.playSound("key-tap"), "Event Router should accept the local preview request");
            long playbackMillis = Math.max(100, (long) Math.ceil(entry.pcm().frames() * 1000.0 / entry.pcm().sampleRate()) + 50);
            Thread.sleep(playbackMillis);
            assertTrue(outputStatus.get().startsWith("Audio output ready:"), "The isolated output must remain open after preview: " + outputStatus.get());
            assertTrue(processFileDescriptors().containsValue("/dev/null"), "The preview must remain attached to /dev/null");
            System.out.println("Isolated Event Router preview reached Java Sound with ALSA default PCM backed by /dev/null; no /dev/snd descriptor opened.");
        } finally {
            engine.close();
        }
    }

    private static Map<Integer, String> processFileDescriptors() throws Exception {
        try (var descriptors = Files.list(Path.of("/proc/self/fd"))) {
            return descriptors.filter(path -> path.getFileName().toString().matches("\\d+"))
                    .map(path -> {
                        try {
                            return Map.entry(Integer.parseInt(path.getFileName().toString()), Files.readSymbolicLink(path).toString());
                        } catch (Exception ignored) {
                            return null;
                        }
                    })
                    .filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue, (first, ignored) -> first));
        }
    }

    private static boolean hasNonzeroSample(float[] samples) {
        for (float sample : samples) if (sample != 0) return true;
        return false;
    }

    private static boolean hasNewDescriptorToDevNull(Map<Integer, String> before, Map<Integer, String> after) {
        return after.entrySet().stream().anyMatch(entry -> entry.getValue().equals("/dev/null")
                && !"/dev/null".equals(before.get(entry.getKey())));
    }
}
