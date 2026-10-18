package io.github.ilyaandreev05.eventsoundstudio.audio;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Opt-in: measures play() to analog-monitor onset through the real persistent pw-cat event stream. */
class EventOutputLiveLatencySmokeTest {
    private static final String DEFAULT_MONITOR = "alsa_output.pci-0000_0b_00.3.analog-stereo.monitor";
    private static final int THRESHOLD = 400, TRIALS = 8;

    @Test void playToAnalogMonitorOnset() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("EVENT_SOUND_STUDIO_LIVE_LATENCY")),
                "Set EVENT_SOUND_STUDIO_LIVE_LATENCY=1 to run the live analog timing pilot");
        String monitor = System.getenv().getOrDefault("EVENT_SOUND_STUDIO_LATENCY_MONITOR", DEFAULT_MONITOR);
        Process capture = new ProcessBuilder("stdbuf", "-o0", "pw-cat", "--record", "--raw", "--target", monitor,
                "--latency=256", "--rate", "48000", "--channels", "2", "--channel-map", "FL,FR",
                "--format", "s16", "-").redirectError(ProcessBuilder.Redirect.INHERIT).start();
        LinuxPipeBudget.shrink(capture, 1);
        Capture reader = new Capture(capture.getInputStream());
        Thread.ofPlatform().daemon().name("analog-onset-reader").start(reader);
        float[] samples = new float[19_200];
        for (int i = 0; i < samples.length; i++) samples[i] = (float) (.8 * Math.sin(2 * Math.PI * 200 * i / 48_000.0));
        PcmSound tone = new PcmSound("latency-probe", "Latency probe", samples, 1, 48_000);
        try (AudioEngine engine = new AudioEngine()) {
            engine.updateSettings(1, false, true, MixerOutputSelector.AUDIO_MATRIX_EVENTS);
            engine.start(null);
            waitReady(engine);
            System.out.println("engine after ready: " + engine.diagnostics());
            Thread.sleep(2_000);
            List<Double> values = new ArrayList<>();
            int missed = 0;
            for (int trial = 0; trial < TRIALS; trial++) {
                Thread.sleep(1_200);
                long start = System.nanoTime();
                reader.arm(start);
                assertTrue(engine.tryPlay(tone, 1, 0, 0, 1, "mix", "latency-probe",
                        MixerOutputSelector.AUDIO_MATRIX_EVENTS));
                long deadline = System.nanoTime() + 1_500_000_000L;
                while (System.nanoTime() < deadline && reader.onset.get() == 0) Thread.sleep(1);
                long onset = reader.onset.get();
                if (onset > start) values.add((onset - start) / 1e6); else missed++;
            }
            assertTrue(values.size() >= TRIALS - 2, "captured only " + values.size() + "/" + TRIALS
                    + " trials; bytes=" + reader.bytes + " peak=" + reader.peak + " diagnostics=" + engine.diagnostics());
            values.sort(Double::compareTo);
            System.out.printf("Live play -> analog monitor onset: detected=%d/%d, p50=%.1f ms, p95=%.1f ms, max=%.1f ms (monitor %s)%n",
                    values.size(), TRIALS, values.get(values.size() / 2),
                    values.get((int) Math.ceil(values.size() * .95) - 1), values.getLast(), monitor);
        } finally {
            capture.destroyForcibly();
        }
    }

    private static void waitReady(AudioEngine engine) throws InterruptedException {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < deadline
                && engine.diagnostics().outputs().stream().noneMatch(AudioEngine.OutputDiagnostics::ready)) Thread.sleep(25);
        assertTrue(engine.diagnostics().outputs().stream().anyMatch(AudioEngine.OutputDiagnostics::ready),
                engine.diagnostics().toString());
    }

    private static final class Capture implements Runnable {
        private final InputStream input;
        private final AtomicLong onset = new AtomicLong();
        private volatile long since, bytes;
        private volatile int peak;
        private Capture(InputStream input) { this.input = input; }
        private void arm(long nanos) { onset.set(0); since = nanos; }
        @Override public void run() {
            byte[] buffer = new byte[65_536];
            try {
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    bytes += count;
                    long now = System.nanoTime();
                    long start = since;
                    if (start == 0 || onset.get() != 0) continue;
                    for (int i = 0; i < count - 1; i += 2) {
                        int sample = (short) ((buffer[i] & 0xFF) | (buffer[i + 1] << 8));
                        if (Math.abs(sample) > peak) peak = Math.abs(sample);
                        if (Math.abs(sample) > THRESHOLD) { onset.compareAndSet(0, now); break; }
                    }
                }
            } catch (Exception ignored) { }
        }
    }
}
