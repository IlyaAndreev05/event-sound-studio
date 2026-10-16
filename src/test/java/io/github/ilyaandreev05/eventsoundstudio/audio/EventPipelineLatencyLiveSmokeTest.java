package io.github.ilyaandreev05.eventsoundstudio.audio;

import io.github.ilyaandreev05.eventsoundstudio.config.ConfigStore;
import io.github.ilyaandreev05.eventsoundstudio.config.DefaultConfig;
import io.github.ilyaandreev05.eventsoundstudio.input.InputEventDispatcher;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.sampled.SourceDataLine;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Measures only synthetic input dispatch through the PCM queue to an injected, non-device output line. */
class EventPipelineLatencyLiveSmokeTest {
    private static final int TRIALS = 40;
    @TempDir Path temp;

    @Test
    @SuppressWarnings("unchecked")
    void syntheticKeyPreviewToFirstPcmBlockLatency() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("EVENT_SOUND_STUDIO_PIPELINE_LATENCY")),
                "Set EVENT_SOUND_STUDIO_PIPELINE_LATENCY=1 to run the fake-line timing pilot");

        AtomicReference<Trial> activeTrial = new AtomicReference<>();
        AudioEngine engine = new AudioEngine(ignored -> recordingLine(activeTrial));
        List<Long> elapsedNanos = new ArrayList<>();
        try (ConfigStore store = new ConfigStore(temp.resolve("config.json"));
             SoundLibrary library = new SoundLibrary(store)) {
            Map<String, Object> config = DefaultConfig.create();
            List<Map<String, Object>> sounds = (List<Map<String, Object>>) config.get("sounds");
            library.loadAll(sounds).get(5, TimeUnit.SECONDS);
            EventRouter router = new EventRouter(engine, library);
            router.update(config);
            try (InputEventDispatcher dispatcher = new InputEventDispatcher(router)) {
                dispatcher.updateConfig(config);
                assertTrue(router.hasEnabledBinding("group:builtin.letters"));

                CountDownLatch outputReady = new CountDownLatch(1);
                engine.start(status -> {
                    if (status.startsWith("Audio output ready:")) outputReady.countDown();
                });
                assertTrue(outputReady.await(2, TimeUnit.SECONDS), "the injected PCM line should open");

                PcmSound sound = library.get("key-tap").pcm();
                long voiceMillis = Math.max(15, (long) Math.ceil(sound.frames() * 1000.0 / sound.sampleRate()) + 15);
                for (int i = 0; i < TRIALS; i++) {
                    Trial trial = new Trial(System.nanoTime());
                    activeTrial.set(trial);
                    dispatcher.previewKey("KEY_A");
                    assertTrue(trial.firstPcm.await(1, TimeUnit.SECONDS), "each synthetic key preview should reach the PCM writer");
                    elapsedNanos.add(trial.elapsedNanos);
                    Thread.sleep(voiceMillis);
                    activeTrial.compareAndSet(trial, null);
                }
            }
        } finally {
            engine.close();
        }

        assertEquals(TRIALS, elapsedNanos.size());
        long[] sorted = elapsedNanos.stream().mapToLong(Long::longValue).sorted().toArray();
        long p50 = percentile(sorted, .50);
        long p95 = percentile(sorted, .95);
        long p99 = percentile(sorted, .99);
        System.out.printf("Synthetic key preview → first PCM block: n=%d, p50=%.2f ms, p95=%.2f ms, p99=%.2f ms; injected SourceDataLine, excludes Java Sound/PipeWire/device latency.%n",
                TRIALS, millis(p50), millis(p95), millis(p99));
    }

    private static long percentile(long[] sorted, double percentile) {
        int index = Math.max(0, (int) Math.ceil(percentile * sorted.length) - 1);
        return sorted[index];
    }

    private static double millis(long nanos) { return nanos / 1_000_000.0; }

    private static SourceDataLine recordingLine(AtomicReference<Trial> activeTrial) {
        return (SourceDataLine) Proxy.newProxyInstance(SourceDataLine.class.getClassLoader(),
                new Class<?>[]{SourceDataLine.class}, (proxy, method, args) -> {
                    if (method.getName().equals("write") && args != null && args.length == 3) {
                        byte[] bytes = (byte[]) args[0];
                        int offset = (int) args[1];
                        int length = (int) args[2];
                        boolean nonzero = false;
                        for (int i = offset; i < offset + length; i++) {
                            if (bytes[i] != 0) { nonzero = true; break; }
                        }
                        Trial trial = activeTrial.get();
                        if (nonzero && trial != null && trial.captured.compareAndSet(false, true)) {
                            trial.elapsedNanos = System.nanoTime() - trial.startedNanos;
                            trial.firstPcm.countDown();
                        }
                        return length;
                    }
                    if (method.getName().equals("toString")) return "InjectedNonDeviceSourceDataLine";
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) return false;
                    if (type == int.class) return 0;
                    if (type == long.class) return 0L;
                    if (type == float.class) return 0f;
                    if (type == double.class) return 0d;
                    if (type == byte.class) return (byte) 0;
                    if (type == short.class) return (short) 0;
                    if (type == char.class) return (char) 0;
                    return null;
                });
    }

    private static final class Trial {
        final long startedNanos;
        final AtomicBoolean captured = new AtomicBoolean();
        final CountDownLatch firstPcm = new CountDownLatch(1);
        volatile long elapsedNanos;
        Trial(long startedNanos) { this.startedNanos = startedNanos; }
    }
}
