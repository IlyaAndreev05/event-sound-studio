package io.github.ilyaandreev05.eventsoundstudio;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Small, deadline-bound local control messages; a stalled client cannot block shutdown. */
final class InstanceTransport {
    private InstanceTransport() { }

    static boolean forward(Path socket, String command) throws IOException {
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
             Selector selector = Selector.open()) {
            channel.configureBlocking(false);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            if (!channel.connect(UnixDomainSocketAddress.of(socket))) {
                while (!channel.finishConnect()) await(channel, selector, SelectionKey.OP_CONNECT, deadline);
            }
            write(channel, selector, command + "\n", deadline);
            return "ok".equals(read(channel, selector, deadline));
        }
    }

    static String receive(SocketChannel channel, java.util.function.Predicate<String> accept) throws IOException {
        try (Selector selector = Selector.open()) {
            channel.configureBlocking(false);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            String command = read(channel, selector, deadline);
            boolean valid = accept.test(command);
            write(channel, selector, valid ? "ok\n" : "error\n", deadline);
            return valid ? command : null;
        }
    }

    private static void write(SocketChannel channel, Selector selector, String message, long deadline) throws IOException {
        ByteBuffer bytes = StandardCharsets.US_ASCII.encode(message);
        while (bytes.hasRemaining()) {
            if (channel.write(bytes) == 0) await(channel, selector, SelectionKey.OP_WRITE, deadline);
        }
    }

    private static String read(SocketChannel channel, Selector selector, long deadline) throws IOException {
        ByteBuffer bytes = ByteBuffer.allocate(32);
        while (bytes.hasRemaining()) {
            int count = channel.read(bytes);
            if (count < 0) throw new IOException("Incomplete instance-control message");
            if (count == 0) { await(channel, selector, SelectionKey.OP_READ, deadline); continue; }
            for (int i = bytes.position() - count; i < bytes.position(); i++) {
                if (bytes.get(i) == '\n') return new String(bytes.array(), 0, i, StandardCharsets.US_ASCII);
            }
        }
        throw new IOException("Instance-control message exceeds 32 bytes");
    }

    private static void await(SocketChannel channel, Selector selector, int operation, long deadline) throws IOException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new IOException("Instance-control request timed out");
        channel.register(selector, operation);
        selector.select(Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
        selector.selectedKeys().clear();
        if (Thread.currentThread().isInterrupted()) throw new IOException("Instance-control request interrupted");
    }
}
