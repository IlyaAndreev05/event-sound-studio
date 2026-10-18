package io.github.ilyaandreev05.eventsoundstudio.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** PipeWire playback target reserved for events routed by Audio Matrix. */
final class PipeWireEventOutput {
    static final String NODE_NAME = "audiomatrix_events";
    static final String APPLICATION_ID = "io.github.ilyaandreev05.eventsoundstudio";

    private PipeWireEventOutput() {}

    static boolean available() {
        return java.util.Arrays.stream(System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator))
                .filter(value -> !value.isBlank()).anyMatch(value -> Files.isExecutable(Path.of(value, "pw-cat")));
    }

    private static final String PIPE_LIMIT = "import fcntl,os,sys\ntry: fcntl.fcntl(0,1031,4096)\nexcept OSError: pass\nos.execvp(sys.argv[1],sys.argv[1:])";

    static List<String> command() {
        String properties = "{\"application.id\":\"" + APPLICATION_ID
                + "\",\"application.name\":\"Event Sound Studio\",\"media.name\":\"Event effects\","
                + "\"node.name\":\"eventsoundstudio_pcm\",\"target.object\":\"" + NODE_NAME + "\",\"node.dont-fallback\":true,\"node.dont-reconnect\":true}";
        List<String> pwCat = List.of("pw-cat", "--playback", "--raw", "--target", NODE_NAME, "--volume=1", "--latency=256",
                "--rate", "48000", "--channels", "2", "--channel-map", "FL,FR",
                "--format", "s16", "--properties=" + properties, "-");
        if (!available("python3")) return pwCat;
        List<String> wrapped = new java.util.ArrayList<>(List.of("python3", "-c", PIPE_LIMIT));
        wrapped.addAll(pwCat);
        return List.copyOf(wrapped);
    }

    private static boolean available(String executable) {
        return java.util.Arrays.stream(System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator))
                .filter(value -> !value.isBlank()).anyMatch(value -> Files.isExecutable(Path.of(value, executable)));
    }

    static SourceDataLine openLine(AudioFormat format) throws IOException {
        if (format.getSampleRate() != 48_000 || format.getSampleSizeInBits() != 16 || format.getChannels() != 2
                || !AudioFormat.Encoding.PCM_SIGNED.equals(format.getEncoding()) || format.isBigEndian() || format.getFrameSize() != 4)
            throw new IOException("Audio Matrix event output supports only 48 kHz signed little-endian 16-bit stereo PCM");
        if (!available()) throw new IOException("pw-cat is unavailable; event audio will remain stopped");
        final int sink;
        try { sink = PipeWireEventTarget.requireUniqueSink(); }
        catch (Exception error) { throw new IOException(error.getMessage(), error); }
        Process process = new ProcessBuilder(command())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        LinuxPipeBudget.shrink(process);
        OutputStream input = process.getOutputStream();
        LinuxPipeBudget.shrink(process);
        try {
            input.write(new byte[1024]); input.flush();
            int stream = -1;
            for (int attempt = 0; attempt < 8 && process.isAlive() && stream < 0; attempt++) {
                stream = PipeWireEventTarget.linkedStream(sink);
                if (stream < 0) Thread.sleep(100);
            }
            if (stream < 0) throw new IOException("Explicit audiomatrix_events target is not linked; event output remains silent");
            PipeWireEventTarget.normalizeLevelAsync(stream, process::isAlive);
        } catch (Exception error) { close(process, input); throw new IOException(error.getMessage(), error); }
        long[] written = {0};
        return (SourceDataLine) Proxy.newProxyInstance(SourceDataLine.class.getClassLoader(),
                new Class<?>[]{SourceDataLine.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "write" -> write(process, input, written, args);
                    case "open", "start", "stop", "flush" -> null;
                    case "close" -> { close(process, input); yield null; }
                    case "isOpen", "isRunning", "isActive" -> process.isAlive();
                    case "getFormat" -> format;
                    case "getLineInfo" -> new DataLine.Info(SourceDataLine.class, format);
                    case "getBufferSize" -> 8192;
                    case "getFramePosition" -> (int) (written[0] / Math.max(1, format.getFrameSize()));
                    case "getLongFramePosition" -> written[0] / Math.max(1, format.getFrameSize());
                    case "getMicrosecondPosition" -> Math.round(written[0] * 1_000_000.0 / format.getFrameRate() / Math.max(1, format.getFrameSize()));
                    case "available" -> 0;
                    case "getLevel" -> 0f;
                    case "toString" -> "PipeWireEventOutput(" + NODE_NAME + ")";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static int write(Process process, OutputStream input, long[] written, Object[] args) {
        byte[] bytes = (byte[]) args[0];
        int offset = (int) args[1], length = (int) args[2];
        if (!process.isAlive()) throw new IllegalStateException("PipeWire event input is unavailable");
        try {
            input.write(bytes, offset, length);
            input.flush();
            written[0] += length;
            return length;
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private static void close(Process process, OutputStream input) {
        // Terminate first: closing a pipe while another thread is blocked in write can wait on its monitor.
        if (process.isAlive()) process.destroyForcibly();
        try { input.close(); } catch (IOException ignored) {}
        if (!process.isAlive()) return;
        try {
            if (!process.waitFor(300, java.util.concurrent.TimeUnit.MILLISECONDS)) process.destroy();
            if (process.isAlive() && !process.waitFor(300, java.util.concurrent.TimeUnit.MILLISECONDS)) process.destroyForcibly();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == long.class) return 0L;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        return 0;
    }
}
