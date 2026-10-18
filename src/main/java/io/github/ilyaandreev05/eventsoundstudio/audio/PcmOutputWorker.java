package io.github.ilyaandreev05.eventsoundstudio.audio;

import javax.sound.sampled.SourceDataLine;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** One persistent writer per route; a suspended device cannot block the mixer or other routes. */
final class PcmOutputWorker implements AutoCloseable {
    private final String selector;
    private final AudioEngine.AudioLineProvider provider;
    private final Consumer<String> listener;
    private final ArrayBlockingQueue<Block> pending = new ArrayBlockingQueue<>(8);
    private final ArrayBlockingQueue<Block> free = new ArrayBlockingQueue<>(9);
    private final AtomicLong played;
    private static final class Block { final byte[] bytes; int starts; Block(int size){bytes=new byte[size];} }
    private final AtomicLong drops = new AtomicLong();
    private final Thread thread;
    private volatile SourceDataLine line;
    private volatile boolean closed, ready;
    private volatile String status = "Waiting for explicit output";
    private volatile long opens, frames;
    private volatile long lastProgressNanos;
    private volatile int bufferBytes;
    private volatile String format = "48000 Hz, signed 16-bit little-endian stereo";

    PcmOutputWorker(String selector, AudioEngine.AudioLineProvider provider, Consumer<String> listener, int blockBytes, AtomicLong played) {
        this.selector = selector; this.provider = provider; this.listener = listener; this.played=played;
        for (int i = 0; i < 9; i++) free.add(new Block(blockBytes));
        thread = Thread.ofPlatform().daemon().name("event-pcm-" + selector.hashCode()).start(this::run);
    }
    boolean ready() { return ready; }
    boolean offer(byte[] block,int starts) {
        if (!ready || closed) { drops.incrementAndGet(); return false; }
        Block buffer = free.poll();
        if (buffer == null) { drops.incrementAndGet(); return false; }
        System.arraycopy(block, 0, buffer.bytes, 0, block.length);buffer.starts=starts;
        if (pending.offer(buffer)) return true;
        free.offer(buffer); drops.incrementAndGet(); return false;
    }
    AudioEngine.OutputDiagnostics diagnostics() {
        boolean stalled=ready&&lastProgressNanos!=0&&System.nanoTime()-lastProgressNanos>2_000_000_000L;
        return new AudioEngine.OutputDiagnostics(selector, stalled?"PCM writer stalled/suspended; hardware delivery unconfirmed":status, ready&&!stalled, opens, Math.max(0, opens - 1), frames,
                drops.get(), bufferBytes, format, pending.size(), stalled);
    }
    private void run() {
        while (!closed) {
            try {
                SourceDataLine opened = provider.open(selector);
                if (closed) { opened.close(); break; }
                line = opened; bufferBytes = opened.getBufferSize();
                if (opened.getFormat() != null) format = opened.getFormat().toString();
                opens++; lastProgressNanos=System.nanoTime(); ready = true; announce("Audio output ready: " + selector);
                while (!closed) {
                    Block block = pending.take();
                    try { writeFully(opened, block.bytes); frames += block.bytes.length / 4; played.addAndGet(block.starts); lastProgressNanos=System.nanoTime(); }
                    finally { free.offer(block); }
                }
            } catch (InterruptedException interrupted) {
                if (closed) break;
            } catch (Exception error) {
                announce((ready ? "Audio output lost: " : "Audio output unavailable: ") + selector + " (" + safe(error) + ")");
            } finally {
                ready = false; closeLine();
                Block block; while ((block = pending.poll()) != null) free.offer(block);
            }
            if (!closed) try { Thread.sleep(1500); } catch (InterruptedException ignored) { }
        }
        status = "Stopped";
    }
    static void writeFully(SourceDataLine line, byte[] bytes) {
        int offset = 0;
        while (offset < bytes.length) {
            int written = line.write(bytes, offset, bytes.length - offset);
            if (written <= 0 || written > bytes.length - offset || written % 4 != 0)
                throw new IllegalStateException("PCM output made no valid frame-aligned progress");
            offset += written;
        }
    }
    private void announce(String value) { status = value; listener.accept(value); }
    private static String safe(Exception error) { return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(); }
    private void closeLine() {
        SourceDataLine current = line; line = null;
        if (current != null) try { current.close(); } catch (RuntimeException ignored) { }
    }
    @Override public void close() { closed = true; ready = false; thread.interrupt(); closeLine(); }
}
