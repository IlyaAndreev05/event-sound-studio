package io.github.ilyaandreev05.eventsoundstudio.audio;

import io.github.ilyaandreev05.eventsoundstudio.config.ConfigStore;
import io.github.ilyaandreev05.eventsoundstudio.config.DefaultConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.sampled.SourceDataLine;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventRouterOutputIsolationTest {
    @TempDir Path temporaryDirectory;

    @Test void eventsUseTheAudioMatrixInputAndGuiPreviewsUseTheirSeparateOutput() throws Exception {
        Set<String> openedOutputs = ConcurrentHashMap.newKeySet();
        CountDownLatch audioBlocks = new CountDownLatch(3);
        AudioEngine engine = new AudioEngine(output -> recordingLine(output, openedOutputs, audioBlocks));
        try (ConfigStore store = new ConfigStore(temporaryDirectory.resolve("config.json"));
             SoundLibrary library = new SoundLibrary(store)) {
            Map<String,Object> config = DefaultConfig.create();
            ((Map<String,Object>) config.get("settings")).put("preview_output", "preview-only-output");
            for(Map<String,Object> binding:(List<Map<String,Object>>)config.get("bindings"))
                if("api_test".equals(binding.get("event")))binding.put("cooldown_ms",10_000);
            library.loadAll((List<Map<String,Object>>) config.get("sounds")).get(5, TimeUnit.SECONDS);
            EventRouter router = new EventRouter(engine, library);
            router.update(config);
            engine.start(ignored -> {});

            assertTrue(router.previewEvent("api_test"), "GUI previews must not consume the live event cooldown");
            assertTrue(router.trigger("api_test"), "a live event after preview must be accepted");
            org.junit.jupiter.api.Assertions.assertFalse(router.trigger("api_test"), "the live event cooldown must still apply");
            assertTrue(router.playSound("key-tap"), "sound previews must be accepted");
            assertTrue(audioBlocks.await(3, TimeUnit.SECONDS), "all routed PCM blocks should reach injected test lines");

            assertEquals(Set.of(MixerOutputSelector.AUDIO_MATRIX_EVENTS, "preview-only-output"), openedOutputs);
        } finally {
            engine.close();
        }
    }

    private static SourceDataLine recordingLine(String output, Set<String> openedOutputs, CountDownLatch audioBlocks) {
        openedOutputs.add(output);
        return (SourceDataLine) Proxy.newProxyInstance(SourceDataLine.class.getClassLoader(),
                new Class<?>[]{SourceDataLine.class}, (proxy, method, args) -> {
                    if (method.getName().equals("write") && args != null && args.length == 3) {
                        byte[] bytes = (byte[]) args[0];
                        int offset = (int) args[1], length = (int) args[2];
                        for (int index = offset; index < offset + length; index++) {
                            if (bytes[index] != 0) {
                                audioBlocks.countDown();
                                break;
                            }
                        }
                        return length;
                    }
                    if (method.getName().equals("toString")) return "InjectedLine(" + output + ")";
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) return false;
                    if (type == int.class) return 0;
                    if (type == long.class) return 0L;
                    if (type == float.class) return 0f;
                    if (type == double.class) return 0d;
                    return null;
                });
    }
}
