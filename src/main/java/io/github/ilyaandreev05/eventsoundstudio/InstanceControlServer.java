package io.github.ilyaandreev05.eventsoundstudio;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** One private control endpoint and process lock per logged-in user. */
public final class InstanceControlServer implements AutoCloseable {
    private static final Set<String> COMMANDS = Set.of("open", "background", "quit");
    private final Path socket;
    private final FileChannel channel;
    private final FileLock lock;
    private final ServerSocketChannel server;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ArrayDeque<String> pending = new ArrayDeque<>();
    private Consumer<String> handler;
    private final Thread listener;

    private InstanceControlServer(Path socket, FileChannel channel, FileLock lock, ServerSocketChannel server) {
        this.socket = socket; this.channel = channel; this.lock = lock; this.server = server;
        listener = Thread.ofPlatform().daemon().name("ess-instance-control").start(this::listen);
    }

    public static Path defaultSocketPath() {
        String runtime = System.getenv("XDG_RUNTIME_DIR");
        Path directory = runtime == null || runtime.isBlank()
                ? Path.of(System.getProperty("user.home"), ".cache", "event-sound-studio", "run")
                : Path.of(runtime, "event-sound-studio");
        return directory.resolve("control.sock").toAbsolutePath().normalize();
    }

    /** Returns null only after another instance acknowledged the command. */
    public static InstanceControlServer acquireOrForward(String command) throws IOException {
        if (!COMMANDS.contains(command)) throw new IllegalArgumentException("Unknown instance command: " + command);
        Path socket = defaultSocketPath();
        Path directory = socket.getParent();
        Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        requireOwned(directory, true);
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        IOException last = null;
        do {
            InstanceControlServer owner = tryOwn(socket);
            if (owner != null) return owner;
            try { if (InstanceTransport.forward(socket, command)) return null; }
            catch (IOException unavailable) { last = unavailable; }
            pause();
        } while (System.nanoTime() < deadline);
        throw new IOException("Event Sound Studio is running or starting but its control socket did not answer", last);
    }

    private static InstanceControlServer tryOwn(Path socket) throws IOException {
        Path lockPath = socket.resolveSibling("control.lock");
        FileChannel channel = FileChannel.open(lockPath,
                Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            requireOwned(lockPath, false);
            Files.setPosixFilePermissions(lockPath, PosixFilePermissions.fromString("rw-------"));
        } catch (IOException error) { channel.close(); throw error; }
        FileLock lock;
        try { lock = channel.tryLock(); }
        catch (OverlappingFileLockException occupied) { channel.close(); return null; }
        if (lock == null) { channel.close(); return null; }
        ServerSocketChannel server = null;
        try {
            Files.deleteIfExists(socket);
            server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            server.bind(UnixDomainSocketAddress.of(socket));
            Files.setPosixFilePermissions(socket, PosixFilePermissions.fromString("rw-------"));
            return new InstanceControlServer(socket, channel, lock, server);
        } catch (IOException error) {
            if (server != null) server.close();
            lock.release(); channel.close(); throw error;
        }
    }

    private static void requireOwned(Path path, boolean directory) throws IOException {
        if (Files.isSymbolicLink(path) || (directory ? !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                : !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                || !Files.getOwner(path, LinkOption.NOFOLLOW_LINKS).equals(Files.getOwner(Path.of("/proc/self"))))
            throw new IOException("Unsafe instance-control path: " + path);
    }

    public static void requestQuitAndWait(Duration timeout) throws IOException {
        InstanceControlServer owner = acquireOrForward("quit");
        if (owner != null) { owner.close(); return; }
        long deadline = System.nanoTime() + timeout.toNanos();
        Path lockPath = defaultSocketPath().resolveSibling("control.lock");
        do {
            try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                try (FileLock claim = channel.tryLock()) { if (claim != null) return; }
                catch (OverlappingFileLockException occupied) { /* Still shutting down in this JVM. */ }
            }
            pause();
        } while (System.nanoTime() < deadline);
        throw new IOException("Event Sound Studio did not stop within " + timeout.toSeconds() + " seconds");
    }

    public synchronized void setHandler(Consumer<String> next) {
        handler = java.util.Objects.requireNonNull(next);
        while (!pending.isEmpty()) handler.accept(pending.removeFirst());
    }

    private synchronized void dispatch(String command) {
        if (handler != null) handler.accept(command);
        else if (pending.size() < 32) pending.addLast(command);
        else if ("quit".equals(command)) { pending.clear(); pending.add(command); }
    }

    private void listen() {
        while (!closed.get()) {
            try (SocketChannel client = server.accept()) {
                String command = InstanceTransport.receive(client, COMMANDS::contains);
                if (command != null) dispatch(command);
            } catch (IOException error) {
                if (!closed.get() && !server.isOpen()) return;
            }
        }
    }

    private static void pause() throws IOException {
        try { Thread.sleep(40); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException("Instance control interrupted", error); }
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try { server.close(); } catch (IOException error) { report(error); }
        try { listener.join(1500); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        try { Files.deleteIfExists(socket); } catch (IOException error) { report(error); }
        try { lock.release(); } catch (IOException error) { report(error); }
        try { channel.close(); } catch (IOException error) { report(error); }
    }

    private static void report(IOException error) { System.err.println("Instance-control cleanup: " + error.getMessage()); }
}
